"""用真实 Git 和 npm 验证本地热更、失败回滚和进程中断恢复。

Verifies local hot updates, rollback, and interrupted recovery with real Git and npm.
"""

import hashlib
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch


OVERLAYS = Path(__file__).resolve().parents[1] / 'overlays'
spec = importlib.util.spec_from_file_location('update_adapter', OVERLAYS / 'android_update.py')
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def fingerprint(directory):
    """为测试工程生成内容指纹。 / Produces a content fingerprint for the test project."""
    return hashlib.sha256((directory / 'build.js').read_bytes()).hexdigest()


def npm_command():
    """在 Windows 和 Linux 上直接执行 npm CLI。 / Executes npm CLI on Windows and Linux."""
    if os.name != 'nt':
        return [shutil.which('npm')]
    node = Path(shutil.which('node'))
    return [str(node), str(node.parent / 'node_modules/npm/bin/npm-cli.js')]


def signal_parent(event, queue):
    """通过跨进程事件和队列通知测试父进程。 / Signals the parent with process-shared IPC."""
    event.set()
    queue.put('ready')


class ServiceContract:
    """只提供上游服务的状态协议，不模拟 Git 或前端构建。

    Supplies only the upstream state contract without simulating Git or frontend builds.
    """

    def __init__(self, root=None, updater=None):
        self.root = Path(root or '.')
        self._ls_remote_cache = None
        self.updater = updater

    def _read_state(self):
        try:
            return json.loads((self.root / adapter.STATE_FILE).read_text(encoding='utf-8'))
        except FileNotFoundError:
            return {}

    def _phase(self, phase):
        state = self._read_state()
        adapter.write_json(self.root / adapter.STATE_FILE,
                           {'phase': phase, 'target': state.get('target')})

    def status(self):
        return {'legacy': True}


@unittest.skipUnless(all(shutil.which(tool) for tool in ('git', 'node', 'npm')),
                     'Requires Git, Node.js, and npm')
class LocalUpdateTest(unittest.TestCase):
    """验证真实文件、Git 对象与 npm 产物的一致性。

    Verifies consistency of real files, Git objects, and npm build outputs.
    """

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.origin = self.base / 'origin'
        self.root = self.base / 'azurpilot'
        self.origin.mkdir()
        adapter.git(self.origin, 'init', '-b', 'dev')
        adapter.git(self.origin, 'config', 'user.name', 'Update test')
        adapter.git(self.origin, 'config', 'user.email', 'update@example.test')
        frontend = self.origin / 'frontend'
        frontend.mkdir()
        (self.origin / '.gitignore').write_text('frontend/dist/\nfrontend/node_modules/\nconfig/\n')
        (self.origin / 'uv.lock').write_text('locked Python environment\n')
        (frontend / 'package.json').write_text(json.dumps({
            'name': 'update-test', 'version': '1.0.0', 'scripts': {'build': 'node build.js'}}))
        (frontend / 'package-lock.json').write_text(json.dumps({
            'name': 'update-test', 'version': '1.0.0', 'lockfileVersion': 3,
            'packages': {'': {'name': 'update-test', 'version': '1.0.0'}}}))
        self.write_builder('old')
        adapter.git(self.origin, 'add', '.')
        adapter.git(self.origin, 'commit', '-m', 'old source')
        self.old = adapter.git(self.origin, 'rev-parse', 'HEAD')
        adapter.git(self.base, 'clone', '--no-hardlinks', str(self.origin), str(self.root))
        (self.root / 'config').mkdir()
        (self.root / 'config/account.db').write_bytes(b'user database')
        self.manifest = {'azurpilot_commit': self.old, 'azurpilot_repo': str(self.origin),
                         'azurpilot_update_repo': str(self.origin),
                         'azurpilot_branch': 'dev', 'hot_update_mode': 'local',
                         'rootfs_version': 'factory-image-1234567890'}
        adapter.write_json(self.root / 'BUILD_MANIFEST', self.manifest)
        self.frontend = SimpleNamespace(source_fingerprint=fingerprint, npm_command=npm_command,
                                        _install_dependencies=lambda cmd, directory, flags:
                                        subprocess.run([*cmd, 'ci', '--no-audit', '--no-fund'],
                                                       cwd=directory, check=True, capture_output=True),
                                        android_manifest=lambda root: json.loads(
                                            (root / 'BUILD_MANIFEST').read_text(encoding='utf-8')))
        adapter.build_frontend(self.frontend, self.root)
        self.state = SimpleNamespace(restart_lock=threading.RLock(), _restart_requested=False,
                                     restart_event=threading.Event(), dependency_sync_event=threading.Event())
        self.cleanup = unittest.mock.Mock(return_value=True)
        self.modules = {
            'module.runtime.setting': SimpleNamespace(State=self.state,
                mark_dependency_sync_pending=lambda: (self.root / 'config/webui-dependency-sync-pending').write_text('pending')),
            'module.runtime.startup_memory': SimpleNamespace(mark_update_restart=lambda: None),
            'module.runtime.process_manager': SimpleNamespace(ProcessManager=SimpleNamespace(
                running_instances=lambda: [SimpleNamespace(config_name='ap')])),
            'module.api.lifecycle': SimpleNamespace(clearup=self.cleanup),
        }
        service_module = SimpleNamespace(AndroidUpdateService=ServiceContract,
            frontend_deploy=self.frontend, logger=SimpleNamespace(exception=lambda *args: None),
            atomic_write=lambda name, body: (self.root / name).write_text(body))
        adapter.patch_service(service_module)
        self.service = service_module.AndroidUpdateService(self.root)
        self.write_builder('new')
        adapter.git(self.origin, 'add', '.')
        adapter.git(self.origin, 'commit', '-m', 'new source')
        self.target = adapter.git(self.origin, 'rev-parse', 'HEAD')

    def write_builder(self, value):
        """生成真正由 npm 执行的构建器。 / Creates a builder executed by real npm."""
        (self.origin / 'frontend/build.js').write_text(
            "const fs = require('fs'); const path = require('path');\n"
            "const dir = process.argv[process.argv.indexOf('--outDir') + 1];\n"
            "fs.mkdirSync(dir, {recursive: true});\n"
            f"fs.writeFileSync(path.join(dir, 'index.html'), '{value}');\n")

    def run_update(self):
        """仅替代上游进程管理对象，真实运行候选准备和文件切换。

        Replaces only upstream process-manager objects while running real preparation and switches.
        """
        adapter.write_json(self.root / adapter.STATE_FILE, {'phase': 'git', 'target': self.target})
        with patch.dict(sys.modules, self.modules):
            self.service._run(self.target)
        return self.service._read_state()

    def assert_old(self):
        """确认回滚保留源码、dist、清单和用户数据。 / Checks source, dist, manifest, and user data."""
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.old)
        self.assertEqual((self.root / 'frontend/dist/index.html').read_text(), 'old')
        self.assertEqual(self.frontend.android_manifest(self.root), self.manifest)
        self.assertEqual((self.root / 'config/account.db').read_bytes(), b'user database')

    def test_success_uses_target_not_manifest_commit(self):
        self.assertTrue(self.service.status()['available'])
        self.assertEqual(self.run_update()['result'], 'updated')
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.target)
        self.assertEqual((self.root / 'frontend/dist/index.html').read_text(), 'new')
        self.assertEqual(self.frontend.android_manifest(self.root)['azurpilot_commit'], self.target)
        self.assertEqual(self.frontend.android_manifest(self.root)['rootfs_version'], self.manifest['rootfs_version'])
        self.assertEqual((self.root / 'config/reloadalas').read_text(), 'ap\n')
        self.assertTrue(self.state.restart_event.is_set())
        self.assertFalse((self.root / adapter.JOURNAL_FILE).exists())

    def test_build_failure_does_not_stop_tasks_or_change_source(self):
        (self.origin / 'frontend/build.js').write_text('process.exit(1);')
        adapter.git(self.origin, 'add', '.')
        adapter.git(self.origin, 'commit', '-m', 'broken frontend')
        self.target = adapter.git(self.origin, 'rev-parse', 'HEAD')
        self.assertEqual(self.run_update()['result'], 'failed')
        self.assert_old()
        self.cleanup.assert_not_called()

    def test_lock_change_no_longer_blocks_hot_update(self):
        """删掉依赖比对后，锁文件差异不再把更新推给整包通道。

        With the dependency comparison removed, a lockfile difference no longer
        defers the update to the full-runtime channel.
        """
        (self.origin / 'uv.lock').write_text('different dependencies\n')
        adapter.git(self.origin, 'add', '.')
        adapter.git(self.origin, 'commit', '-m', 'changed lock')
        self.target = adapter.git(self.origin, 'rev-parse', 'HEAD')
        state = self.run_update()
        self.assertEqual(state['result'], 'updated')
        self.assertFalse(state.get('requiresRuntimeUpdate'))
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.target)
        self.assertEqual((self.root / 'uv.lock').read_text(), 'different dependencies\n')
        self.assertFalse(self.service.status()['requiresRuntimeUpdate'])

    def test_manifest_commit_mismatch_no_longer_blocks_hot_update(self):
        """清单记录的提交与 HEAD 不一致时仍可更新（手动改过清单的场景）。

        A manifest commit that differs from HEAD still allows the update, which is
        what happens after the manifest is edited by hand.
        """
        adapter.write_json(self.root / 'BUILD_MANIFEST',
                           dict(self.manifest, azurpilot_commit='f' * 40))
        self.assertEqual(self.run_update()['result'], 'updated')
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.target)
        self.assertEqual(self.frontend.android_manifest(self.root)['azurpilot_commit'], self.target)

    def test_failure_after_reset_rolls_back_dist_and_manifest(self):
        actual = adapter.write_json
        def fail_manifest(path, value):
            if Path(path).name == 'BUILD_MANIFEST' and value['azurpilot_commit'] == self.target:
                raise OSError('disk full')
            actual(path, value)
        with patch.object(adapter, 'write_json', side_effect=fail_manifest):
            self.assertEqual(self.run_update()['result'], 'failed')
        self.assert_old()
        self.assertTrue(self.state.restart_event.is_set())

    def test_interrupted_switch_recovers_on_start(self):
        stage = adapter.workspace(self.root)
        stage.mkdir()
        adapter.write_json(self.root / adapter.JOURNAL_FILE,
                           {'oldHead': self.old, 'manifest': self.manifest})
        (self.root / 'frontend/dist').replace(stage / 'dist.previous')
        adapter.git(self.root, 'fetch', 'origin', self.target)
        adapter.git(self.root, 'reset', '--hard', self.target)
        adapter.recover(self.root)
        self.assert_old()
        self.assertEqual(self.service._read_state()['phase'], 'done')

    def test_preparation_interruption_clears_busy(self):
        adapter.write_json(self.root / adapter.STATE_FILE, {'phase': 'dist', 'target': self.target})
        adapter.recover(self.root)
        self.assertFalse(self.service.status()['busy'])
        self.assert_old()

    def test_matching_fingerprint_skips_npm(self):
        with patch.object(self.frontend, 'npm_command', side_effect=AssertionError('unneeded build')):
            adapter.build_frontend(self.frontend, self.root)

    def test_legacy_runtime_keeps_upstream_service(self):
        manifest = dict(self.manifest, hot_update_mode='prebuilt')
        adapter.write_json(self.root / 'BUILD_MANIFEST', manifest)
        self.assertEqual(self.service.status(), {'legacy': True})

    def test_busy_status_does_not_query_network(self):
        adapter.write_json(self.root / adapter.STATE_FILE, {'phase': 'dist', 'target': self.target})
        with patch.object(self.service, 'upstream_head', side_effect=AssertionError('network while busy')):
            self.assertTrue(self.service.status()['busy'])

    def test_missing_supervisor_does_not_touch_live_tree(self):
        self.state.restart_event = None
        self.assertEqual(self.run_update()['result'], 'failed')
        self.assert_old()

    def test_reload_migration_preserves_other_deploy_settings(self):
        deploy = self.root / 'config/deploy.yaml'
        initial = 'Deploy:\n  Update:\n    EnableReload: false # keep comment\n    CheckUpdateInterval: 0\n'
        deploy.write_text(initial)
        adapter.ensure_reload(self.root)
        self.assertEqual(deploy.read_text(), initial.replace('EnableReload: false', 'EnableReload: true'))
        adapter.ensure_reload(self.root)
        self.assertEqual(deploy.read_text(), initial.replace('EnableReload: false', 'EnableReload: true'))

    def test_corrupt_checkout_requires_full_runtime(self):
        with patch.object(adapter, 'git', side_effect=subprocess.CalledProcessError(128, ['git'])):
            self.assertTrue(self.run_update()['requiresRuntimeUpdate'])
        self.assert_old()

    def test_sources_returns_only_the_manifest_update_repository(self):
        custom = 'https://git.example/AzurPilot-fork.git'
        adapter.write_json(self.root / 'BUILD_MANIFEST',
                           dict(self.manifest, azurpilot_repo='https://github.com/wess09/AzurPilot.git',
                                azurpilot_update_repo=custom))
        repositories, branch = self.service.sources()
        self.assertEqual(repositories, [custom])
        self.assertEqual(branch, 'dev')

    def test_mirror_prefix_no_longer_rewrites_source(self):
        custom = 'https://github.com/someone/AzurPilot-fork.git'
        adapter.write_json(self.root / 'BUILD_MANIFEST',
                           dict(self.manifest, azurpilot_update_repo=custom))
        with patch.dict(os.environ, AZURPILOT_ANDROID_GIT_MIRROR='https://mirror.example/'):
            repositories, _ = self.service.sources()
        self.assertEqual(repositories, [custom])

    def test_app_setting_repo_overrides_manifest(self):
        """设置页填的仓库优先于构建清单，清单地址不可达也能正常更新。

        The repository typed in Settings wins over the build manifest, so an
        unreachable manifest address still updates successfully.
        """
        adapter.write_json(self.root / 'BUILD_MANIFEST',
                           dict(self.manifest, azurpilot_update_repo=str(self.base / 'missing-repository')))
        with patch.dict(os.environ, {adapter.OVERRIDE_REPO_ENV: str(self.origin)}):
            self.assertEqual(self.service.sources()[0], [str(self.origin)])
            self.assertEqual(self.run_update()['result'], 'updated')
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.target)

    def test_app_setting_branch_overrides_manifest(self):
        """设置页填的分支优先于清单分支。

        The branch typed in Settings wins over the manifest branch.
        """
        adapter.write_json(self.root / 'BUILD_MANIFEST',
                           dict(self.manifest, azurpilot_branch='missing-branch'))
        self.assertEqual(self.service.sources()[1], 'missing-branch')
        with patch.dict(os.environ, {adapter.OVERRIDE_BRANCH_ENV: 'dev'}):
            self.assertEqual(self.service.sources()[1], 'dev')

    def test_missing_update_repo_reports_unconfigured_source(self):
        manifest = dict(self.manifest)
        manifest.pop('azurpilot_update_repo')
        adapter.write_json(self.root / 'BUILD_MANIFEST', manifest)
        self.assertEqual(self.service.sources(), ([], 'dev'))
        self.assertEqual(self.service.upstream_head(), '')
        status = self.service.status()
        self.assertFalse(status['available'])
        self.assertIn('azurpilot_update_repo', status['error'])
        state = self.run_update()
        self.assertEqual(state['result'], 'failed')
        self.assertIn('azurpilot_update_repo', state['error'])
        # 清单本身被本用例改过，不能复用 assert_old 的整份清单比对。
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.old)
        self.assertEqual(self.frontend.android_manifest(self.root), manifest)
        self.assertEqual((self.root / 'frontend/dist/index.html').read_text(), 'old')

    def test_unreachable_update_repo_fails_without_fallback(self):
        broken = dict(self.manifest, azurpilot_update_repo=str(self.base / 'missing-repository'))
        adapter.write_json(self.root / 'BUILD_MANIFEST', broken)
        self.assertEqual(self.run_update()['result'], 'failed')
        # 单一来源：取不到就直接失败，不会改从 azurpilot_repo 再试一次。
        self.assertEqual(adapter.git(self.root, 'rev-parse', 'HEAD'), self.old)
        self.assertEqual((self.root / 'frontend/dist/index.html').read_text(), 'old')
        self.assertEqual(self.frontend.android_manifest(self.root), broken)

    def test_failed_recovery_keeps_journal_and_dist_backup(self):
        actual = adapter.write_json
        def fail_manifest(path, value):
            if Path(path).name == 'BUILD_MANIFEST':
                raise OSError('disk full')
            actual(path, value)
        with patch.object(adapter, 'write_json', side_effect=fail_manifest), \
                patch.object(adapter, 'recover', side_effect=OSError('rollback failed')):
            self.assertEqual(self.run_update()['result'], 'failed')
        self.assertTrue((self.root / adapter.JOURNAL_FILE).exists())
        self.assertTrue((adapter.workspace(self.root) / 'dist.previous').is_dir())
        self.assertFalse(self.state.restart_event.is_set())
        adapter.recover(self.root)
        self.assert_old()


class ImportAdapterTest(unittest.TestCase):
    """验证钩子幂等性和旧前端委派。 / Verifies hook idempotence and legacy frontend delegation."""

    def test_install_is_idempotent(self):
        before = list(sys.meta_path)
        try:
            adapter.install()
            adapter.install()
            self.assertEqual(sum(getattr(item, '_android_update_adapter', False)
                                 for item in sys.meta_path), 1)
        finally:
            sys.meta_path[:] = before

    def test_old_frontend_delegates(self):
        module = ModuleType('frontend_fixture')
        module.ensure_frontend = unittest.mock.Mock()
        original = module.ensure_frontend
        adapter.patch_frontend(module)
        with tempfile.TemporaryDirectory() as directory:
            module.ensure_frontend(directory)
            original.assert_called_once_with(Path(directory))

    @unittest.skipUnless(os.name == 'posix', 'Requires POSIX guest')
    def test_supervisor_events_and_queues_cross_process_boundary(self):
        context = multiprocessing.get_context('spawn')
        event = context.Event()
        queue = context.Queue()
        process = context.Process(target=signal_parent, args=(event, queue))
        try:
            process.start()
            self.assertEqual(queue.get(timeout=20), 'ready')
            process.join(20)
            self.assertEqual(process.exitcode, 0)
            self.assertTrue(event.is_set())
        finally:
            if process.is_alive():
                process.terminate()
                process.join(5)
            queue.close()
            queue.join_thread()

    @unittest.skipUnless(os.name == 'posix', 'Requires POSIX guest')
    def test_host_child_exit_with_open_stdin_does_not_abort_interpreter(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'host'
            binary = root / '.venv/bin/python'
            binary.parent.mkdir(parents=True)
            binary.symlink_to(sys.executable)
            for name in ('android_host.py', 'android_update.py'):
                shutil.copyfile(OVERLAYS / name, root / name)
            (root / 'gui.py').write_text('print("CHILD_EXIT_OK")\n')
            process = subprocess.Popen([sys.executable, str(root / 'android_host.py')],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env={**os.environ, 'AZURPILOT_ANDROID': '0'})
            try:
                self.assertEqual(process.wait(timeout=20), 0)
            finally:
                if process.poll() is None:
                    process.kill()
                process.stdin.close()
                process.stdin = None
                stdout, stderr = process.communicate(timeout=5)
            self.assertIn(b'CHILD_EXIT_OK', stdout)
            self.assertNotIn(b'Fatal Python error', stderr)


if __name__ == '__main__':
    unittest.main()
