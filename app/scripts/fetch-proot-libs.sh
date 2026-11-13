#!/usr/bin/env bash
# 从固定的 Termux Debian 包生成 x86_64 PRoot 原生库九件套。
#
# CI 在构建 x86_64 Runtime 前调用本脚本，产物写入 app/app/src/main/prootLibs/x86_64/；
# arm64-v8a 使用仓内已验证的固定产物。Termux 二进制会改名为 lib*.so 并以同长字符串
# 改写 DT_NEEDED，使 AGP 将其安装到 nativeLibraryDir——targetSdk 限制下唯一可靠可
# execve 的 APK 私有位置。所有包版本和 SHA-256 固定，生成物不提交。
#
# Builds the x86_64 PRoot native-library set from pinned Termux Debian packages.
# CI calls this before building the x86_64 runtime and writes output to
# app/app/src/main/prootLibs/x86_64/; arm64-v8a uses verified pinned artifacts in the repository.
# Termux binaries are renamed to lib*.so and their DT_NEEDED strings are rewritten in place so AGP
# installs them in nativeLibraryDir, the only APK-private location reliably execve-able under
# targetSdk restrictions. Package versions and SHA-256 values are pinned; generated output is not
# committed.
#
# 依赖关系 / Dependency mapping:
#   libproot.so        <- Termux proot: usr/bin/proot (NEEDED libtalloc.so.2 -> libtalloc.so)
#   libproot-loader.so <- Termux proot: usr/libexec/proot/loader (static ELF guest launcher)
#   libtalloc.so       <- libtalloc: usr/lib/libtalloc.so.2.5.0
#   libandroid-shmem.so / libandroid-selinux.so / libpcre2-8.so <- same-named packages, renamed
#   libbusybox.so      <- busybox: usr/bin/busybox (NEEDED -> libbusybox_app.so)
#   libbusybox_app.so  <- busybox: usr/lib/libbusybox.so.1.38.0 (applet payload; SONAME unchanged)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/app/app/src/main/prootLibs/x86_64"
TMP_DIR="${TMPDIR:-/tmp}/fetch-proot-libs-x86_64"
POOL="https://packages.termux.dev/apt/termux-main/pool/main"

# 包名_版本 -> data.tar 内相对 data/data/com.termux/files/usr 的路径；SHA-256 固定为
# 已验证快照，升级时必须同时更新来源、哈希和下方 ELF 计划。
#
# Package-name_version maps to a path under data.tar's data/data/com.termux/files/usr. SHA-256
# pins a verified snapshot; upgrades must update the source, hash, and ELF plan below together.
PKGS=(
    "proot_5.1.107.102_x86_64.deb|2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758"
    "libtalloc_2.5.0_x86_64.deb|b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530"
    "libandroid-shmem_0.7_x86_64.deb|ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"
    "libandroid-selinux_14.0.0.11-1_x86_64.deb|99cf96556683ddb53f7d645ca1720e10523c4796ce5b41c583da9f89a47679ce"
    "pcre2_10.49_x86_64.deb|4a6f66ac12565342897ce9b5cd11ba8ce6fce1fd1a91ae679c3468d7fc2ee541"
    "busybox_1.38.0-1_x86_64.deb|519b57623dd076b4d6cf6d389ed976dd222410e3a0b9b9b58c14d8535b6eef48"
)

# Windows 的 python3 可能是 Microsoft Store 占位 stub，必须实际执行 import 才算可用。
# python3 on Windows may be a Microsoft Store placeholder; it must execute an import before use.
PY=""
for cand in python3 python; do
    if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys' 2>/dev/null; then
        PY="$cand"
        break
    fi
done
[[ -n $PY ]] || { echo '需要 python3 或 python' >&2; exit 1; }
command -v curl >/dev/null || { echo '需要 curl' >&2; exit 1; }

mkdir -p "$TMP_DIR" "$OUT_DIR"

for entry in "${PKGS[@]}"; do
    deb="${entry%%|*}"
    sha="${entry##*|}"
    target="$TMP_DIR/$deb"
    if [[ -s $target && "$(sha256sum "$target" | awk '{print $1}')" == "$sha" ]]; then
        echo "cached ok: $deb"
    else
        echo "downloading $deb"
        name="${deb%%_*}"
        # Debian pool 路径规则：lib* 取前四字符，其余包取首字符；不能由 URL 猜测后静默
        # 回退，否则镜像布局变化会掩盖错误。
        # Debian pool path rules use four leading characters for lib* and one for other packages;
        # do not silently guess a fallback URL or a mirror-layout change could hide an error.
        prefix_dir="${name:0:4}"
        [[ $name == lib* ]] || prefix_dir="${name:0:1}"
        # Termux 会滚动删除旧版本 deb，404 时给出可操作的提示，而不是只留 curl 退出码 22。
        # Termux rotates old deb versions away; on 404 explain the cause instead of a bare
        # curl exit code 22.
        curl -fsL --retry 3 "$POOL/$prefix_dir/$name/$deb" -o "$target" || {
            echo "下载失败：$POOL/$prefix_dir/$name/$deb" >&2
            echo "Termux 可能已删除该版本；用 project/upstream-switch/check_termux_pkgs.py 查现行版本后，同步更新 PKGS/PLAN 与 SHA-256。" >&2
            exit 1
        }
        actual="$(sha256sum "$target" | awk '{print $1}')"
        [[ "$actual" == "$sha" ]] || { echo "$deb SHA256 mismatch: $actual" >&2; exit 1; }
    fi
done

# 解包、改名、同长 DT_NEEDED 改写与 ELF 校验放在一个 Python 进程中完成，避免中间产物被
# AGP 或并行任务观察到。该内嵌程序只接受已完成 SHA-256 校验的 deb。
#
# Unpack, rename, equal-length DT_NEEDED rewrite, and ELF validation run in one Python process so
# AGP or parallel tasks cannot observe intermediate output. This embedded program accepts only
# deb files whose SHA-256 verification already completed.
"$PY" - "$TMP_DIR" "$OUT_DIR" <<'PY'
"""将已校验的 x86_64 PRoot 包物化为 AGP 可打包的 lib*.so 集合。

调用方在执行前校验包哈希。本程序仅提取 PLAN 指定文件，做有界的同长 DT_NEEDED 替换，校验
x86_64 ELF 头，并删除本脚本管理的过期产物。

Materializes verified x86_64 PRoot packages as an AGP-packagable lib*.so set.

The caller verifies package hashes before this code runs. This program extracts only files named
in PLAN, performs bounded equal-length DT_NEEDED substitutions, validates x86_64 ELF headers, and
removes stale artifacts managed by this script.
"""
import io, lzma, os, shutil, struct, sys, tarfile

tmp_dir, out_dir = sys.argv[1], sys.argv[2]
PREFIX = './data/data/com.termux/files/usr/'

# (deb, 源路径, 输出名, [(旧 NEEDED 名, 新 NEEDED 名)])。替换必须不比原字符串长，
# 因为 ELF 动态字符串表的偏移不可在此重定位。
#
# (deb, source path, output name, [(old NEEDED name, new NEEDED name)]). A replacement may not be
# longer than the original because this script cannot relocate ELF dynamic-string-table offsets.
PLAN = [
    ('proot_5.1.107.102_x86_64.deb', 'bin/proot', 'libproot.so',
     [('libtalloc.so.2', 'libtalloc.so')]),
    ('proot_5.1.107.102_x86_64.deb', 'libexec/proot/loader', 'libproot-loader.so', []),
    ('libtalloc_2.5.0_x86_64.deb', 'lib/libtalloc.so.2.5.0', 'libtalloc.so', []),
    ('libandroid-shmem_0.7_x86_64.deb', 'lib/libandroid-shmem.so', 'libandroid-shmem.so', []),
    ('libandroid-selinux_14.0.0.11-1_x86_64.deb', 'lib/libandroid-selinux.so', 'libandroid-selinux.so', []),
    ('pcre2_10.49_x86_64.deb', 'lib/libpcre2-8.so', 'libpcre2-8.so', []),
    ('busybox_1.38.0-1_x86_64.deb', 'bin/busybox', 'libbusybox.so',
     [('libbusybox.so.1.38.0', 'libbusybox_app.so')]),
    ('busybox_1.38.0-1_x86_64.deb', 'lib/libbusybox.so.1.38.0', 'libbusybox_app.so', []),
]


def data_tar(deb_path):
    """从一个已校验的 Debian archive 返回 data.tar 载荷。

    抛出：
        AssertionError: archive 不是 ar/deb 文件，或不存在 data.tar 成员。

    Returns the data.tar payload from one verified Debian archive.

    Raises:
        AssertionError: The archive is not an ar/deb file or has no data.tar member.
    """
    with open(deb_path, 'rb') as f:
        blob = f.read()
    assert blob[:8] == b'!<arch>\n', f'{deb_path} 不是 deb(ar) 包'
    off = 8
    while off + 60 <= len(blob):
        hdr = blob[off:off + 60]
        name = hdr[0:16].decode().strip().rstrip('/')
        size = int(hdr[48:58].decode().strip())
        body = blob[off + 60:off + 60 + size]
        if name.startswith('data.tar.'):
            raw = lzma.decompress(body) if name.endswith('.xz') else body
            return tarfile.open(fileobj=io.BytesIO(raw), mode='r:')
        off += 60 + size + (size % 2)
    raise AssertionError(f'{deb_path} 缺少 data.tar')


tars = {}
for deb, src, dest, patches in PLAN:
    if deb not in tars:
        tars[deb] = data_tar(os.path.join(tmp_dir, deb))
    member = next((m for m in tars[deb].getmembers()
                   if m.isfile() and m.name == PREFIX + src), None)
    if member is None:
        raise AssertionError(f'{deb} 里找不到 {src}')
    blob = tars[deb].extractfile(member).read()
    for old, new in patches:
        old_b, new_b = old.encode() + b'\0', new.encode()
        assert len(new_b) <= len(old_b) - 1, f'{dest}: {new} 放不进 {old} 的位置'
        assert blob.count(old_b) == 1, f'{dest}: 期望恰好 1 处 {old}'
        blob = blob.replace(old_b, new_b + b'\0' * (len(old_b) - len(new_b)))
    # 全部应为 64 位小端 ELF；proot 二进制与 loader 是 ET_EXEC/ET_DYN 均可
    assert blob[:4] == b'\x7fELF' and blob[4] == 2 and blob[5] == 1, f'{dest} 不是 64 位 ELF'
    machine = struct.unpack_from('<H', blob, 18)[0]
    assert machine == 62, f'{dest} machine={machine}，期望 x86_64(62)'
    with open(os.path.join(out_dir, dest), 'wb') as g:
        g.write(blob)
    print(f'  {dest}  {len(blob)} bytes')

# 改写后的引用必须准确落位；这既确认 ABI 重命名，也防止上游包布局静默漂移。
# Rewritten references must land exactly; this confirms ABI renaming and prevents silent upstream
# package-layout drift.
proot = open(os.path.join(out_dir, 'libproot.so'), 'rb').read()
assert b'libtalloc.so\0' in proot and b'libtalloc.so.2\0' not in proot
stub = open(os.path.join(out_dir, 'libbusybox.so'), 'rb').read()
assert b'libbusybox_app.so\0' in stub and b'libbusybox.so.1.38.0\0' not in stub

# 清除上次遗留的受管产物，避免 AGP 意外打包已不在 PLAN 中的旧 ABI 库。
# Remove stale managed artifacts so AGP cannot accidentally package an old ABI library no longer
# listed in PLAN.
for stale in os.listdir(out_dir):
    if stale not in {dest for _, _, dest, _ in PLAN}:
        os.remove(os.path.join(out_dir, stale))
        print(f'  removed stale {stale}')
print('OK: prootLibs/x86_64 就绪')
PY
