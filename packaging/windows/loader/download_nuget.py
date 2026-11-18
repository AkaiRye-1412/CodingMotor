# -*- coding: utf-8 -*-
"""下载 WebView2 NuGet 包并解压出需要的 DLL。"""
import os, json, urllib.request, zipfile, io, shutil

LOADER_DIR = os.path.dirname(os.path.abspath(__file__))
LIB_DIR = os.path.join(LOADER_DIR, "lib")
PKG_ID = "microsoft.web.webview2"
INDEX = f"https://api.nuget.org/v3-flatcontainer/{PKG_ID}/index.json"

def latest_stable():
    with urllib.request.urlopen(INDEX, timeout=30) as r:
        data = json.load(r)
    versions = data.get("versions", [])
    # 过滤预发布（只取纯数字版本）
    stable = [v for v in versions if "-" not in v and "+" not in v]
    if not stable:
        raise RuntimeError("没有稳定版本")
    return stable[-1]

def download(version):
    url = f"https://api.nuget.org/v3-flatcontainer/{PKG_ID}/{version}/{PKG_ID}.{version}.nupkg"
    print("下载:", url)
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()

def extract(nupkg_bytes):
    if os.path.isdir(LIB_DIR):
        shutil.rmtree(LIB_DIR)
    os.makedirs(LIB_DIR)
    wanted = {
        # .NET Framework 4.6.2 目标的托管 DLL（4.8 也用这个）
        "lib/net462/Microsoft.Web.WebView2.Core.dll": "Microsoft.Web.WebView2.Core.dll",
        "lib/net462/Microsoft.Web.WebView2.WinForms.dll": "Microsoft.Web.WebView2.WinForms.dll",
        "lib/net462/Microsoft.Web.WebView2.Shell.Interop.dll": "Microsoft.Web.WebView2.Shell.Interop.dll",
        # x64 native loader
        "runtimes/win-x64/native/WebView2Loader.dll": "WebView2Loader.dll",
    }
    with zipfile.ZipFile(io.BytesIO(nupkg_bytes)) as z:
        names = z.namelist()
        got = []
        for member, dest in wanted.items():
            # NuGet 包里路径可能大小写不一，做不敏感匹配
            match = next((n for n in names if n.lower() == member.lower()), None)
            if match:
                with z.open(match) as src:
                    data = src.read()
                open(os.path.join(LIB_DIR, dest), "wb").write(data)
                got.append(dest)
                print("  提取:", dest, len(data), "bytes")
            else:
                # 列出相近路径帮助诊断
                print("  未找到:", member)
        if not got:
            print("包内前 30 项:", names[:30])
            raise RuntimeError("未提取到任何 DLL")
    print("lib 目录:", sorted(os.listdir(LIB_DIR)))

if __name__ == "__main__":
    ver = latest_stable()
    print("最新稳定版:", ver)
    data = download(ver)
    print("nupkg 大小:", len(data), "bytes")
    extract(data)
    print("完成")
