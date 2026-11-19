#!/usr/bin/env python3
"""准备与 LiteRT 同版的厂商 JIT 库，按哈希校验并仅提取白名单文件。

构建产物写入被忽略的 .tmp/ocr-runtime；不运行下载包内的脚本。APK 内同时带上
来源清单和 SDK 许可文本。升级 LiteRT 时须重新核对插件、QNN 和 NeuroPilot 的版本。

Prepares matching LiteRT vendor JIT libraries using hashes and allowlisted extraction.
Writes ignored .tmp/ocr-runtime outputs without executing downloaded scripts. APKs include
source manifests and SDK license texts. Recheck all vendor versions when upgrading LiteRT.
"""

import hashlib
import json
import shutil
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / ".tmp/ocr-runtime"
VERSION = "2.1.0rc1"
SOURCES = {
    "litert-jit.zip": (
        f"https://github.com/google-ai-edge/LiteRT/releases/download/v{VERSION}/litert_npu_runtime_libraries_jit.zip",
        "7c1cf0e466daccebb884e2b7dc626ac263103adb7f2a51912d7b1f69d4012615",
    ),
    "qnn-runtime.aar": (
        "https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.40.0/qnn-runtime-2.40.0.aar",
        "a39572b77013f2c58657d05f08cad36ab256cc44402df746de5850b3b6d94397",
    ),
    "neuropilot.tar.gz": (
        "https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz",
        "f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994",
    ),
}


def digest(path):
    """计算文件哈希。 / Computes a file hash."""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


# 上游 CDN 会偶发回 404（Maven Central 走 Cloudflare，缓存刷新窗口里会短暂取不到
# release 资产）。这类 404 与「包真的没了」不可区分，所以不能一次判死：按退避重试，
# 每次都做完整校验，最终仍失败才抛出。成功路径不产生任何额外网络请求。
# Upstream CDNs occasionally answer 404 for a release asset that clearly exists
# (Maven Central sits behind Cloudflare and does this during cache rotation). A 404
# is indistinguishable from "asset gone", so retry with backoff instead of failing
# once; every attempt is checksum-verified. The success path adds no extra requests.
ATTEMPTS = 5
BACKOFF_SECONDS = (0, 10, 30, 60)


def download(name, url, expected):
    """下载完整文件并校验，失败不留下可用缓存。 / Downloads and verifies complete files."""
    cache = OUTPUT / "downloads"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / name
    if target.is_file() and digest(target) == expected:
        return target
    temporary = target.with_suffix(".partial")
    last_error = None
    for attempt in range(1, ATTEMPTS + 1):
        delay = BACKOFF_SECONDS[min(attempt - 1, len(BACKOFF_SECONDS) - 1)]
        if delay:
            print(f"Retrying {name} in {delay}s (attempt {attempt}/{ATTEMPTS})", flush=True)
            time.sleep(delay)
        print(f"Downloading {name} (attempt {attempt}/{ATTEMPTS})", flush=True)
        try:
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
                shutil.copyfileobj(response, out)
        except (urllib.error.URLError, OSError) as error:
            # URLError 覆盖 HTTPError（404/5xx）、连接重置和超时；残缺文件下一轮会被覆盖。
            last_error = error
            if temporary.exists():
                temporary.unlink()
            print(f"Download of {name} failed: {error}", flush=True)
            continue
        if digest(temporary) != expected:
            # 校验不符多为截断或镜像陈旧，重试有意义；清掉残片再试。
            actual = digest(temporary)
            temporary.unlink()
            last_error = ValueError(f"Checksum mismatch: {name} (got {actual}, want {expected})")
            print(str(last_error), flush=True)
            continue
        temporary.replace(target)
        return target
    raise RuntimeError(f"Could not fetch {name} after {ATTEMPTS} attempts: {last_error}")


def main():
    """生成库目录和许可清单。 / Generates library directories and license manifests."""
    archives = {name: download(name, *source) for name, source in SOURCES.items()}
    libraries = OUTPUT / "jni/arm64-v8a"
    notices = OUTPUT / "assets/ocr/licenses"
    libraries.mkdir(parents=True, exist_ok=True)
    notices.mkdir(parents=True, exist_ok=True)
    bundled = {}

    def write(name, data):
        destination = libraries / name
        destination.write_bytes(data)
        bundled[name] = hashlib.sha256(data).hexdigest()

    with zipfile.ZipFile(archives["litert-jit.zip"]) as archive:
        for vendor, folder in [("Qualcomm", "qualcomm_runtime_v69"), ("MediaTek", "mediatek_runtime")]:
            for kind in ["CompilerPlugin", "Dispatch"]:
                name = f"libLiteRt{kind}_{vendor}.so"
                installed_name = "libLiteRtDispatch_MediaTek_Vendor.so" if vendor == "MediaTek" and kind == "Dispatch" else name
                write(installed_name, archive.read(f"{folder}/src/main/jni/arm64-v8a/{name}"))

    with zipfile.ZipFile(archives["qnn-runtime.aar"]) as archive:
        names = ["libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so"]
        names += [f"libQnnHtpV{version}{kind}.so"
                  for version in [68, 69, 73, 75, 79, 81] for kind in ["Skel", "Stub"]]
        for name in names:
            write(name, archive.read(f"jni/arm64-v8a/{name}"))
        for name in archive.namelist():
            if not name.endswith("/") and any(key in name.lower() for key in ["license", "notice"]):
                (notices / f"qnn-{Path(name).name}").write_bytes(archive.read(name))

    with tarfile.open(archives["neuropilot.tar.gz"]) as archive:
        for name, member in {
            "libneuronusdk_adapter.mtk.so": "neuro_pilot/v8_0_10/usdk/lib64/libneuronusdk_adapter.mtk.so",
            "libneuronusdk_adapter.9.mtk.so": "neuro_pilot/v9_0_3/usdk/lib64/libneuronusdk_adapter.so",
        }.items():
            with archive.extractfile(member) as stream:
                write(name, stream.read())
        with archive.extractfile("neuro_pilot/LICENSE AGREEMENT.pdf") as stream:
            (notices / "neuropilot-license.pdf").write_bytes(stream.read())

    from prepare_hiai_runtime import HIAI_LIBRARIES, prepare
    # HiAI 由独立来源准备；清理 LiteRT 旧版库时保留它的白名单。
    for path in libraries.iterdir():
        if path.name not in bundled and path.name not in HIAI_LIBRARIES:
            path.unlink()
    manifest = {
        "litert": VERSION, "qnn": "2.40.0", "neuropilot": ["8.0.10", "9.0.3"],
        "sources": {name: {"url": url, "sha256": checksum}
                    for name, (url, checksum) in SOURCES.items()},
        "libraries": bundled,
    }
    (OUTPUT / "assets/ocr/runtime.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(bundled)} matching arm64 NPU libraries", flush=True)
    prepare()


if __name__ == "__main__":
    main()
