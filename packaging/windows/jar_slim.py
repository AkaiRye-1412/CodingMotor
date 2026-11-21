# -*- coding: utf-8 -*-
"""
fat jar 瘦身：把 onnxruntime 里运行期用不到的原生库条目剔掉。

onnxruntime 的 jar 带 win/linux/osx × x64/aarch64 六套原生库（解开后 290MB）。
其中 **win-x64/onnxruntime.pdb 一个就占 290MB 里的绝大部分**（压缩后 58MB）——
PDB 是调试符号，运行时根本不加载；osx 的两套还额外挂了 .dSYM 调试符号。

绿色包只跑 Windows x64，因此只保留 `win-x64/*.dll`，其余全裁掉。
仓库里的构建产物仍可跑其他平台，只有打包产物是裁剪版。

单独成模块是为了可测：`python jar_slim.py <fat.jar> <out.jar>`。
"""
import io
import os
import sys
import zipfile

NATIVE_PREFIX = "ai/onnxruntime/native/"
NATIVE_KEEP = "ai/onnxruntime/native/win-x64/"
ONNXRUNTIME_PREFIX = "BOOT-INF/lib/onnxruntime-"
# 调试符号：运行期不加载，体积却是最大头
DEBUG_SUFFIXES = (".pdb",)
DEBUG_MARKERS = (".dSYM/",)


def _is_debug_artifact(name):
    return name.endswith(DEBUG_SUFFIXES) or any(m in name for m in DEBUG_MARKERS)


def _drop_native(name):
    """native/ 下该不该丢：非 win-x64 全丢；win-x64 里只留 .dll。"""
    if not name.startswith(NATIVE_PREFIX):
        return False
    if not name.startswith(NATIVE_KEEP):
        return True
    return _is_debug_artifact(name)


def _copy_entry(zin, zout, item):
    """按原压缩方式逐条复制（Spring Boot 的嵌套 jar 必须保持 STORED，不能一律重压）。"""
    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
    zi.compress_type = item.compress_type
    zi.external_attr = item.external_attr
    zout.writestr(zi, zin.read(item.filename))


def slim_nested_native(jar_bytes):
    """剔除非 win-x64 的原生库与调试符号，返回 (新 jar 字节, 剔除条目数)。"""
    out = io.BytesIO()
    removed = 0
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as zin, \
            zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
        for item in zin.infolist():
            if _drop_native(item.filename):
                removed += 1
                continue
            _copy_entry(zin, zout, item)
    return out.getvalue(), removed


def slim_fat_jar(src_jar, dst_jar):
    """复制 fat jar，顺带裁掉 onnxruntime 的多平台原生库；返回新大小（字节）。"""
    with zipfile.ZipFile(src_jar) as zin, \
            zipfile.ZipFile(dst_jar, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
        for item in zin.infolist():
            if item.filename.startswith(ONNXRUNTIME_PREFIX) and item.filename.endswith(".jar"):
                data, removed = slim_nested_native(zin.read(item.filename))
                print(f"  裁剪 {item.filename}：移除 {removed} 个非 win-x64 原生库条目")
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zout.writestr(zi, data)
            else:
                _copy_entry(zin, zout, item)
    return os.path.getsize(dst_jar)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("用法: python jar_slim.py <fat.jar> <out.jar>")
        sys.exit(2)
    # slim_fat_jar 只返回新大小，旧大小要自己取（原来按元组解包，CLI 一跑就 TypeError）
    old = os.path.getsize(sys.argv[1])
    new = slim_fat_jar(sys.argv[1], sys.argv[2])
    print(f"{old/1024/1024:.1f} MB -> {new/1024/1024:.1f} MB（省 {(old-new)/1024/1024:.1f} MB）")
