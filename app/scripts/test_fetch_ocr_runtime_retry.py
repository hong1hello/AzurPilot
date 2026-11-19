"""回归：fetch_ocr_runtime.download 必须扛住上游偶发 404。

背景：2026-11-19 的发布构建在第 13 步失败，Maven Central 对
qnn-runtime-2.40.0.aar 回了一次 404，脚本一次判死。补丁改为按退避重试，
本测试用假的 urlopen 验证：
  1) 前 N 次 404、之后成功 —— 最终拿到文件；
  2) 校验不符会清掉残片并重试；
  3) 全失败时抛 RuntimeError，不留 .partial；
  4) 成功路径只请求一次。
"""

import hashlib
import importlib.util
import sys
import tempfile
import unittest
import urllib.error
import pathlib
from io import BytesIO
from unittest import mock

_SCRIPT = pathlib.Path(__file__).resolve().with_name("fetch_ocr_runtime.py")
_spec = importlib.util.spec_from_file_location("fetch_ocr_runtime_under_test", _SCRIPT)
target = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = target
_spec.loader.exec_module(target)

PAYLOAD = b"qnn-runtime-payload" * 1024
EXPECTED = hashlib.sha256(PAYLOAD).hexdigest()


class FakeResponse(BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def make_urlopen(sequence):
    """sequence 里每项是 bytes（成功）或 Exception（抛出）。"""
    calls = []

    def fake(url, timeout=None):
        index = min(len(calls), len(sequence) - 1)
        item = sequence[index]
        calls.append(url)
        if isinstance(item, Exception):
            raise item
        return FakeResponse(item)

    return fake, calls


class DownloadRetryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patcher = mock.patch.object(target, "OUTPUT", pathlib.Path(self.tmp.name))
        patcher.start()
        self.addCleanup(patcher.stop)
        sleep_patcher = mock.patch.object(target.time, "sleep")
        self.sleep = sleep_patcher.start()
        self.addCleanup(sleep_patcher.stop)

    def test_recovers_after_transient_404(self):
        not_found = urllib.error.HTTPError("u", 404, "Not Found", None, None)
        fake, calls = make_urlopen([not_found, not_found, PAYLOAD])
        with mock.patch.object(target.urllib.request, "urlopen", fake):
            path = target.download("qnn-runtime.aar", "http://example/x.aar", EXPECTED)
        self.assertEqual(path.read_bytes(), PAYLOAD)
        self.assertEqual(len(calls), 3)
        self.assertEqual(self.sleep.call_count, 2)

    def test_retries_on_checksum_mismatch(self):
        fake, calls = make_urlopen([b"truncated", PAYLOAD])
        with mock.patch.object(target.urllib.request, "urlopen", fake):
            path = target.download("qnn-runtime.aar", "http://example/x.aar", EXPECTED)
        self.assertEqual(path.read_bytes(), PAYLOAD)
        self.assertEqual(len(calls), 2)

    def test_gives_up_without_leaving_partial(self):
        not_found = urllib.error.HTTPError("u", 404, "Not Found", None, None)
        fake, calls = make_urlopen([not_found])
        with mock.patch.object(target.urllib.request, "urlopen", fake):
            with self.assertRaises(RuntimeError):
                target.download("qnn-runtime.aar", "http://example/x.aar", EXPECTED)
        self.assertEqual(len(calls), target.ATTEMPTS)
        leftovers = list((pathlib.Path(self.tmp.name) / "downloads").glob("*.partial"))
        self.assertEqual(leftovers, [])

    def test_success_path_requests_once(self):
        fake, calls = make_urlopen([PAYLOAD])
        with mock.patch.object(target.urllib.request, "urlopen", fake):
            target.download("qnn-runtime.aar", "http://example/x.aar", EXPECTED)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.sleep.call_count, 0)

    def test_cache_hit_skips_network(self):
        target.OUTPUT.mkdir(parents=True, exist_ok=True)
        cache = target.OUTPUT / "downloads"
        cache.mkdir(parents=True, exist_ok=True)
        (cache / "qnn-runtime.aar").write_bytes(PAYLOAD)
        fake, calls = make_urlopen([PAYLOAD])
        with mock.patch.object(target.urllib.request, "urlopen", fake):
            target.download("qnn-runtime.aar", "http://example/x.aar", EXPECTED)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
