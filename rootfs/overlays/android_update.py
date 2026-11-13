"""用运行时内置 Git 和 Node.js 更新源码与前端，保留上游恢复协议。

源码只从构建清单记录的自定义仓库获取：官方源的 CNB 优先、GitHub 镜像前缀与
构建来源回退均已屏蔽，任何更新都不得改从其它地址取代码。

候选源码与前端在正式目录外准备；锁文件变化交给整包更新。切换前保存恢复日志，
进程中断后由 android_host 在启动 WebUI 前回滚。旧运行时继续使用预构建前端。

Updates source and frontend with bundled Git and Node.js using upstream recovery protocols.

Source comes solely from the custom repository recorded in the build manifest: the
official CNB preference, GitHub mirror prefixes, and build-source fallback are all
removed, so no update may fetch code from another address.

Prepares candidates outside the live tree and delegates lockfile changes to full updates.
A journal allows android_host to roll back interrupted switches before starting the WebUI.
Older runtimes continue using prebuilt frontend assets.
"""

import datetime
import hashlib
import importlib.abc
import importlib.machinery
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


STATE_FILE = 'config/android-update-state.json'
JOURNAL_FILE = 'config/android-update-transaction.json'
# 清单里缺失该字段时不再回退到任何内置地址，直接按"更新源未配置"处理。
# A missing field no longer falls back to any built-in address; it is treated
# as an unconfigured update source instead.
UPDATE_REPO_KEY = 'azurpilot_update_repo'
# App 设置页写入的自定义源码来源，优先级高于构建清单。
# Custom source written by the app settings screen; it wins over the build manifest.
OVERRIDE_REPO_ENV = 'AZURPILOT_ANDROID_SOURCE_REPO'
OVERRIDE_BRANCH_ENV = 'AZURPILOT_ANDROID_SOURCE_BRANCH'

MAX_REPOSITORY_LENGTH = 300
MAX_BRANCH_LENGTH = 80


def normalize_repository(value):
    """校验更新源地址；不合法返回空串，由调用方按"未配置"处理。

    这两个值最终会进 ``git ls-remote`` 的实参位置。Git 会把以 ``-`` 开头的实参当选项，
    ``ls-remote`` 支持 ``--upload-pack=<exec>``，因此未经校验的字符串等于一条命令执行
    通路。这里只做结构性拦截，不强制是 URL——本地路径与 ssh 形态在测试与工具链里合法，
    但不放行空白、控制字符、前导 ``-`` 与 ``..``。

    Validates an update-source address; returns an empty string for anything invalid.

    The value ends up in an argument position of ``git ls-remote``. Git parses arguments
    beginning with ``-`` as options, and ``ls-remote`` accepts ``--upload-pack=<exec>``, so an
    unvalidated string is effectively a command-execution path. It does not require a URL
    (local paths and ssh forms are legal for tests and tooling); it only blocks whitespace,
    control characters, a leading ``-`` and ``..``.
    """
    text = (value or '').strip()
    if not text or len(text) > MAX_REPOSITORY_LENGTH:
        return ''
    if any(character.isspace() or not character.isprintable() for character in text):
        return ''
    # Git 把 "-" 开头的实参当选项（ls-remote 的 --upload-pack 可执行命令），
    # ".." 则可能被解释成上级目录，两者都不放行。
    if text.startswith('-') or '..' in text:
        return ''
    return text


def normalize_branch(value):
    """校验分支名；不合法返回空串，由调用方回退到默认分支。

    同样是为了不让 ``refs/heads/`` 后面的那一段被当成选项或路径穿越。

    Validates a branch name; returns an empty string for anything invalid, so the segment
    after ``refs/heads/`` can never become an option or a traversal.
    """
    text = (value or '').strip()
    if not text or len(text) > MAX_BRANCH_LENGTH:
        return ''
    if text.startswith(('/', '-')) or '..' in text:
        return ''
    return text if all(character.isalnum() or character in '._/-' for character in text) else ''


def write_json(path, value):
    """原子保存更新状态或恢复日志。 / Atomically saves update state or the recovery journal."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def now():
    """返回 UTC 状态时间。 / Returns the UTC state timestamp."""
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def tools_ready(root):
    """只为已内置工具链的新镜像启用本地更新。 / Enables local updates only for equipped images."""
    try:
        manifest = json.loads((Path(root) / 'BUILD_MANIFEST').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return False
    return (manifest.get('hot_update_mode') == 'local' and
            all(shutil.which(tool) for tool in ('git', 'node', 'npm')))


def git(root, *args, timeout=900):
    """限时执行 Git，失败时把 stderr 一并抛出去。

    原来直接让 ``CalledProcessError`` 冒到状态里，界面只能看到
    ``Command '[...]' returned non-zero exit status 128.`` —— 128 只是「git 失败」的
    统称，真正的 DNS / TLS / 权限原因全在 stderr 里，于是用户和日志都停在无信息的一句话上。
    这里把 stderr 尾部与常见成因的提示拼进异常文本。

    Runs bounded Git commands and folds stderr into the raised error.

    Letting ``CalledProcessError`` escape gave the UI nothing but
    ``... returned non-zero exit status 128.`` — 128 is git's catch-all failure code and the
    actual DNS/TLS/permission reason only lives in stderr. The tail and a cause hint are
    appended here so the state file and the log carry something actionable.
    """
    try:
        result = subprocess.run(
            # 阈值放宽到 256 B/s、容忍 60s：国内直连 GitHub 常见「能连但很慢」，
            # 原来的 1024 B/s × 30s 会把还在推进的传输判死。
            ['git', '-c', 'http.lowSpeedLimit=256', '-c', 'http.lowSpeedTime=60', *args],
            cwd=root, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace',
            timeout=timeout, env={**os.environ, 'GIT_TERMINAL_PROMPT': '0'},
        )
    except subprocess.TimeoutExpired as error:
        # 超时不是 CalledProcessError，得单独兜：否则会冒到上层变成一句无信息的 repr
        raise RuntimeError(
            f'git {args[0]} 超时（{timeout}s）：网络能到 GitHub 但太慢，'
            '可开全局代理或改用更快的网络后重试'
        ) from error
    except subprocess.CalledProcessError as error:
        detail = ((error.stderr or '') or (error.stdout or '')).strip()
        tail = detail.splitlines()[-3:] if detail else []
        summary = ' / '.join(line.strip() for line in tail)[-400:] or 'git 无输出'
        hint = ''
        lowered = detail.lower()
        if 'could not resolve host' in lowered or 'name or service not known' in lowered:
            hint = '（容器内 DNS 解析失败：手机网络到不了 GitHub，检查是否需要全局代理）'
        elif 'ssl' in lowered or 'certificate' in lowered:
            hint = '（TLS 失败：可能是中间人代理或容器缺少 CA 证书）'
        elif 'authentication failed' in lowered or 'not found' in lowered or 'could not read username' in lowered:
            hint = '（仓库不可读：地址写错，或该仓库是私有的）'
        elif 'timed out' in lowered or 'timeout' in lowered:
            hint = '（连接超时：网络不通或被限速）'
        raise RuntimeError(f'git {args[0]} 失败：{summary}{hint}') from error
    return result.stdout.strip()


def workspace(root):
    """返回与正式源码同文件系统的更新目录。 / Returns an update workspace on the same filesystem."""
    return Path(root).parent / 'azurpilot-update'


def ensure_reload(root):
    """为本地热更启用上游父监督器，保持更新定时器及用户配置不变。

    Enables the upstream reload supervisor for local updates without changing timers or user settings.
    """
    if not tools_ready(root):
        return
    path = Path(root) / 'config/deploy.yaml'
    text = path.read_text(encoding='utf-8')
    updated = re.sub(r'(?m)^([ \t]*EnableReload:[ \t]*)false([ \t]*(?:#.*)?)$',
                     r'\g<1>true\g<2>', text, count=1)
    if updated != text:
        temporary = path.with_name(path.name + '.tmp')
        temporary.write_text(updated, encoding='utf-8')
        temporary.replace(path)


def recover(root):
    """在 WebUI 未运行时恢复未完成的源码、前端和清单切换；回滚失败时禁止启动。

    Restores interrupted source, frontend, and manifest switches before WebUI startup;
    a failed rollback prevents startup.
    """
    root = Path(root)
    journal = root / JOURNAL_FILE
    if journal.exists():
        state = json.loads(journal.read_text(encoding='utf-8'))
        old = state['oldHead']
        if not re.fullmatch(r'[0-9a-f]{40}', old):
            raise RuntimeError('更新恢复日志中的提交无效')
        for name in ('index.lock', 'HEAD.lock'):
            (root / '.git' / name).unlink(missing_ok=True)
        git(root, 'reset', '--hard', old)
        backup = workspace(root) / 'dist.previous'
        if backup.exists():
            shutil.rmtree(root / 'frontend/dist', ignore_errors=True)
            backup.replace(root / 'frontend/dist')
        write_json(root / 'BUILD_MANIFEST', state['manifest'])
        journal.unlink()
        write_json(root / STATE_FILE, {'phase': 'done', 'result': 'failed',
                                      'error': '更新中断，已恢复旧版本', 'updatedAt': now()})
    # 准备阶段尚未改正式版本，但 daemon 线程被杀后不能永久保留 busy 状态。
    state_path = root / STATE_FILE
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if state.get('phase') not in (None, 'done'):
            write_json(state_path, {'phase': 'done', 'result': 'failed',
                                    'error': '更新中断，请重试', 'updatedAt': now()})
    shutil.rmtree(workspace(root), ignore_errors=True)


def build_frontend(module, root):
    """按上游源码指纹和 npm 锁文件构建，构建失败保留现有 dist。

    Builds using upstream fingerprints and the npm lockfile, retaining the existing dist on failure.
    """
    directory = Path(root) / 'frontend'
    fingerprint = module.source_fingerprint(directory)
    marker = directory / 'dist/.source-fingerprint'
    if ((directory / 'dist/index.html').is_file() and marker.is_file() and
            marker.read_text(encoding='utf-8').strip() == fingerprint):
        return
    temporary = directory / '.android-dist'
    previous = directory / '.android-dist.previous'
    shutil.rmtree(temporary, ignore_errors=True)
    command = module.npm_command()
    module._install_dependencies(command, directory, {})
    try:
        subprocess.run([*command, 'run', 'build', '--', '--outDir', str(temporary.resolve())],
                       cwd=directory, check=True, timeout=600, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
        if not (temporary / 'index.html').is_file():
            raise RuntimeError('前端构建未生成页面入口')
        (temporary / '.source-fingerprint').write_text(fingerprint + '\n', encoding='utf-8')
        shutil.rmtree(previous, ignore_errors=True)
        dist = directory / 'dist'
        if dist.exists():
            dist.replace(previous)
        try:
            temporary.replace(dist)
        except Exception:
            if previous.exists():
                previous.replace(dist)
            raise
        shutil.rmtree(previous, ignore_errors=True)
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def verify_candidate(root, candidate, timeout=300):
    """换目录之前，用现有解释器把新源码的入口模块跑一遍导入。

    热更不能装依赖，所以「新分支要了新依赖」这类问题只有在换过去之后才会暴露，
    表现是 WebUI 起不来、状态却写着 updated。在候选目录里先导入一次，把这种失败
    挡在挂起任务与换目录之前：此时正式目录一字未动，回滚成本为零。

    Imports the candidate's entry modules with the current interpreter before switching.

    A hot update cannot install dependencies, so a branch needing a new one would only show
    up as a dead WebUI while the state file claims "updated". Importing first moves that
    failure in front of the suspend/swap steps, where the live tree is still untouched.
    """
    python = Path(root) / '.venv/bin/python'
    if not python.exists():
        return
    result = subprocess.run(
        [str(python), '-c', 'import module.api.app, deploy.frontend'],
        cwd=candidate, capture_output=True, text=True, encoding='utf-8', errors='replace',
        timeout=timeout,
        env={**os.environ, 'AZURPILOT_ANDROID': '1', 'PYTHONDONTWRITEBYTECODE': '1'},
    )
    if result.returncode != 0:
        lines = (result.stderr or result.stdout or '').strip().splitlines()
        detail = lines[-1] if lines else 'unknown import error'
        raise RuntimeError(f'新源码导入失败，已放弃切换：{detail[-300:]}')


def patch_frontend(module):
    """为新镜像接入本地构建，旧镜像保留上游路径。 / Adds local builds while keeping legacy behavior."""
    original = module.ensure_frontend

    def ensure_frontend(root=None):
        root = Path(root) if root else Path(module.__file__).resolve().parents[1]
        if tools_ready(root):
            return build_frontend(module, root)
        return original(root)

    module.ensure_frontend = ensure_frontend


def patch_service(module):
    """替换 Android 更新服务的准备与切换阶段，保留 HTTP 接口及上游重载协议。

    Replaces preparation and switching while retaining HTTP APIs and upstream reload protocols.
    """
    original = module.AndroidUpdateService

    class LocalUpdateService(original):
        """在正式源码外准备可回滚的更新。 / Prepares reversible updates outside the live tree."""

        # 最近一次上游探测的失败原因；成功即清空，与 _ls_remote_cache 同步刷新。
        # Last upstream probe failure; cleared on success and refreshed with _ls_remote_cache.
        _ls_remote_error = ''

        def sources(self):
            """返回当前生效的更新源，优先取 App 设置的自定义仓库。

            解析顺序：环境变量（App 设置页）> 构建清单 ``azurpilot_update_repo``。
            两者都没有时返回空列表，由调用方决定是让应用更新失败还是把
            "更新源未配置"透出到状态里，避免把配置缺失伪装成"已是最新"。
            分支同理：环境变量 > ``azurpilot_branch`` > ``dev``。

            / Returns the effective update source, preferring the app-configured repository.

            Resolution order: environment override (app settings) > manifest
            ``azurpilot_update_repo``. When neither exists an empty list is returned so the
            caller can either fail the apply or surface "update source not configured"
            instead of disguising a missing configuration as "already up to date". The
            branch resolves the same way: override > ``azurpilot_branch`` > ``dev``.
            """
            manifest = module.frontend_deploy.android_manifest(self.root)
            repository = (normalize_repository(os.environ.get(OVERRIDE_REPO_ENV, ''))
                          or normalize_repository(str(manifest.get(UPDATE_REPO_KEY) or '')))
            branch = (normalize_branch(os.environ.get(OVERRIDE_BRANCH_ENV, ''))
                      or normalize_branch(str(manifest.get('azurpilot_branch') or ''))
                      or 'dev')
            return ([repository] if repository else []), branch

        def upstream_head(self, force=False):
            """探测自定义更新源的 HEAD，失败原因经 status() 透出。

            只有一个源、没有回退链：探测失败就是失败，不会被"换个地址再试"
            掩盖。裸吞异常会把「检查失败」伪装成「已是最新」（error 恒空、
            available 恒 false），失败原因带 git stderr 尾部以便定位 DNS/TLS。
            / Probes the custom source HEAD and surfaces the failure reason via status().

            There is one source and no fallback chain: a failed probe stays failed
            instead of being masked by a retry against another address. Swallowed
            exceptions disguise "check failed" as "already up to date" (empty error,
            false available); the reason carries the git stderr tail so DNS/TLS
            failures are directly diagnosable.
            """
            if not tools_ready(self.root):
                return super().upstream_head(force)
            cached = self._ls_remote_cache
            if not force and cached and time.monotonic() - cached[0] < 60:
                return cached[1]
            repositories, branch = self.sources()
            if not repositories:
                self._ls_remote_cache = (time.monotonic(), '')
                self._ls_remote_error = (f'未配置更新源：设置页源码仓库为空，且清单缺少 '
                                         f'{UPDATE_REPO_KEY}')
                return ''
            repository = repositories[0]
            head = ''
            failure = ''
            try:
                output = git(self.root, 'ls-remote', repository, f'refs/heads/{branch}', timeout=15)
                head = output.split()[0] if output else ''
            except (OSError, subprocess.SubprocessError) as error:
                detail = (getattr(error, 'stderr', None) or str(error)).strip()
                failure = f'{repository}: {detail}'[-300:]
            if not re.fullmatch(r'[0-9a-f]{40}', head):
                head = ''
                failure = failure or f'{repository}: 未取到 refs/heads/{branch} 的有效提交'
            self._ls_remote_cache = (time.monotonic(), head)
            self._ls_remote_error = failure
            return head

        def dist_ready(self, commit, force=False):
            return True if tools_ready(self.root) else super().dist_ready(commit, force)

        def status(self):
            if not tools_ready(self.root):
                return super().status()
            state = self._read_state()
            local = module.frontend_deploy.android_manifest(self.root).get('azurpilot_commit')
            busy = state.get('phase') not in (None, 'done')
            upstream = state.get('target') if busy else self.upstream_head()
            full = bool(upstream and state.get('target') == upstream and
                        state.get('requiresRuntimeUpdate'))
            # 任务自身的错误优先；仅空闲时透出探测失败——busy 期间不调
            # upstream_head()，旧错误会失真（App 侧已有「热更失败+原因」渲染分支）。
            # Task errors win; probe failures surface only while idle — upstream_head()
            # isn't called while busy, so a stale error would mislead (the app already
            # renders the "hot update failed + reason" branch).
            error = state.get('error', '') or ('' if busy else self._ls_remote_error)
            return {'enabled': True, 'localHead': local, 'upstreamHead': upstream or None,
                    'available': bool(upstream and local and local != upstream and not full),
                    'distReady': True, 'busy': busy, 'phase': state.get('phase'),
                    'target': state.get('target'), 'error': error,
                    'updatedAt': state.get('updatedAt'), 'requiresRuntimeUpdate': full}

        def _run(self, target):
            if not tools_ready(self.root):
                return super()._run(target)
            from module.runtime.setting import State, mark_dependency_sync_pending
            from module.runtime.startup_memory import mark_update_restart

            def reload():
                with State.restart_lock:
                    if State._restart_requested:
                        raise RuntimeError('WebUI 已请求重启')
                    from module.api.lifecycle import clearup
                    if not clearup():
                        raise RuntimeError('运行中的任务未能安全停止')
                    mark_update_restart()
                    State.dependency_sync_event.set()
                    State._restart_requested = True
                    State.restart_event.set()

            suspended = False
            full = False
            stage = workspace(self.root)
            try:
                if State.restart_event is None or State.dependency_sync_event is None:
                    raise RuntimeError('父监督器不可用，无法安全重启')
                # 首次升级旧镜像仍走旧通道；新镜像必须有可用于回滚的浅克隆。
                try:
                    old_head = git(self.root, 'rev-parse', 'HEAD')
                except (OSError, subprocess.SubprocessError) as error:
                    full = True
                    raise RuntimeError('源码仓库损坏，请更新完整运行时') from error
                manifest = module.frontend_deploy.android_manifest(self.root)
                repositories, _ = self.sources()
                if not repositories:
                    raise RuntimeError('未配置更新源：请在设置页填写源码仓库，或使用含 '
                                       f'{UPDATE_REPO_KEY} 的运行时')
                shutil.rmtree(stage, ignore_errors=True)
                stage.mkdir()
                candidate = stage / 'source'
                git(stage, 'clone', '--local', '--no-hardlinks', '--no-checkout',
                    str(self.root), str(candidate))
                # 单一来源：不做多源重试，失败直接走下面的回滚路径。
                # Single source: no multi-source retry; failure goes straight to rollback.
                git(candidate, 'remote', 'set-url', 'origin', repositories[0])
                git(candidate, 'fetch', '--depth', '1', 'origin', target)
                git(candidate, 'reset', '--hard', target)
                # 不再比对 uv.lock，也不与清单记录交叉核对：自定义分支可与内置 Runtime
                # 的依赖集不同，由使用者自行保证可运行。依赖安装仍是关闭的，热更不会
                # 联网装包；清单里的 uv_lock_sha256 照实记录当次实际值。
                # No uv.lock comparison and no cross-check against the manifest record: a
                # custom branch may differ from the built-in dependency set and the user owns
                # that risk. Dependency installation stays disabled so hot updates never
                # install packages over the network; the manifest still records the real hash.
                self._phase('dist')
                build_frontend(module.frontend_deploy, candidate)
                # 事后体检的前置版：新源码必须能在现有解释器里导入。放在挂起任务之前，
                # 失败时正式目录还没动过，不会出现「状态说更新成功、实际起不来」。
                verify_candidate(self.root, candidate)
                from module.runtime.process_manager import ProcessManager
                names = [instance.config_name + '\n' for instance in ProcessManager.running_instances()]
                mark_dependency_sync_pending()
                module.atomic_write('./config/reloadalas', ''.join(names))
                from module.api.lifecycle import clearup
                suspended = True
                if not clearup():
                    raise RuntimeError('运行中的任务未能安全停止')
                self._phase('manifest')
                # 正式源码只在构建成功后变化；journal 先落盘才能移动旧 dist。
                write_json(self.root / JOURNAL_FILE, {'oldHead': old_head, 'manifest': manifest})
                (self.root / 'frontend/dist').replace(stage / 'dist.previous')
                git(self.root, 'fetch', '--depth', '1', str(candidate), target)
                git(self.root, 'reset', '--hard', target)
                (candidate / 'frontend/dist').replace(self.root / 'frontend/dist')
                manifest.update(azurpilot_commit=target,
                                frontend_sha256=hashlib.sha256(
                                    (self.root / 'frontend/dist/index.html').read_bytes()).hexdigest(),
                                uv_lock_sha256=hashlib.sha256((self.root / 'uv.lock').read_bytes()).hexdigest(),
                                built_at_utc=now())
                write_json(self.root / 'BUILD_MANIFEST', manifest)
                self._phase('reload')
                # 先写完成态再重启，否则新 WebUI 可能继承 busy，永久阻塞后续更新。
                write_json(self.root / STATE_FILE, {'phase': 'done', 'result': 'updated',
                                                  'target': target, 'error': '', 'updatedAt': now()})
                reload()
                (self.root / JOURNAL_FILE).unlink()
            except Exception as error:
                module.logger.exception('[Android-热更] 本地更新失败')
                # 回滚失败保留 journal，让下一次启动再次恢复，不能删除唯一的旧 dist。
                recover_error = None
                try:
                    if (self.root / JOURNAL_FILE).exists():
                        recover(self.root)
                except Exception as rollback_error:
                    recover_error = rollback_error
                write_json(self.root / STATE_FILE, {'phase': 'done', 'result': 'failed',
                                                  'target': target, 'requiresRuntimeUpdate': full,
                                                  'error': (getattr(recover_error or error, 'stderr', None)
                                                            or str(recover_error or error))[-300:],
                                                  'updatedAt': now()})
                if suspended and recover_error is None and not State._restart_requested:
                    try:
                        reload()
                    except Exception:
                        module.logger.exception('[Android-热更] 恢复任务失败')
            finally:
                if not (self.root / JOURNAL_FILE).exists():
                    shutil.rmtree(stage, ignore_errors=True)

    module.AndroidUpdateService = LocalUpdateService
    module.android_update_service = LocalUpdateService()


class _Loader(importlib.abc.Loader):
    """在上游模块初始化后接入适配器。 / Connects adapters after upstream initialization."""

    def __init__(self, original, patch):
        self.original = original
        self.patch = patch

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        self.patch(module)


class _Finder(importlib.abc.MetaPathFinder):
    """只适配前端部署和 Android 更新模块。 / Adapts only frontend deployment and Android updates."""

    _android_update_adapter = True
    patches = {'deploy.frontend': patch_frontend, 'module.api.android_update': patch_service}

    def find_spec(self, fullname, path, target=None):
        if fullname not in self.patches:
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec and spec.loader:
            spec.loader = _Loader(spec.loader, self.patches[fullname])
        return spec


def install():
    """幂等安装延迟钩子，不提前导入上游。 / Installs lazy hooks idempotently without loading upstream."""
    if any(getattr(finder, '_android_update_adapter', False) for finder in sys.meta_path):
        return
    sys.meta_path.insert(0, _Finder())
    for name, patch in _Finder.patches.items():
        if name in sys.modules:
            patch(sys.modules[name])
