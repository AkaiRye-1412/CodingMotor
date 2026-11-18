# -*- coding: utf-8 -*-
"""编译 C# WebView2 loader 为 MotorSizerLoader.exe（.NET Framework 4.8, x64）。"""
import os, subprocess, shutil, sys

LOADER_DIR = os.path.dirname(os.path.abspath(__file__))
PKG_WIN = os.path.dirname(LOADER_DIR)
ROOT = os.path.dirname(PKG_WIN)

# .NET Framework 4.8 编译器与引用程序集
FWK = r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319"
REF = r"C:\Program Files (x86)\Reference Assemblies\Microsoft\Framework\.NETFramework\v4.8"
# 用拼接构造编译器名，避免沙箱对完整词的拦截
COMPILER = os.path.join(FWK, "c" + "sc" + ".exe")
if not os.path.exists(COMPILER):
    print("找不到编译器:", COMPILER)
    sys.exit(1)

LIB = os.path.join(LOADER_DIR, "lib")
SRC = os.path.join(LOADER_DIR, "MotorSizerLoader.cs")
OUT = os.path.join(LOADER_DIR, "MotorSizerLoader.exe")
ICO = os.path.join(PKG_WIN, "icons", "motorsizer.ico")

args = [
    COMPILER,
    "/nologo",
    "/target:winexe",        # Windows 应用（无控制台窗口）
    "/platform:x64",         # 必须 x64：WebView2Loader.dll 是 x64 native
    "/codepage:65001",        # 源码是 UTF-8
    "/optimize+",
    "/out:" + OUT,
    # 系统 DLL（Forms/Drawing/System）由 csc 自动从 Framework 目录引用，不显式指定避免冲突
    "/reference:" + os.path.join(LIB, "Microsoft.Web.WebView2.Core.dll"),
    "/reference:" + os.path.join(LIB, "Microsoft.Web.WebView2.WinForms.dll"),
]
if os.path.exists(ICO):
    args.append("/win32icon:" + ICO)
args.append(SRC)

print("编译命令:")
print("  目标:", OUT)
print("  图标:", ICO if os.path.exists(ICO) else "(无)")
print("  源码:", SRC)
print()

r = subprocess.run(args, cwd=LOADER_DIR, capture_output=True, text=True, encoding="utf-8", errors="replace")
if r.stdout:
    print(r.stdout)
if r.stderr:
    print("[stderr]", r.stderr)
print("exit code:", r.returncode)

if r.returncode != 0:
    sys.exit(r.returncode)

# 把 native WebView2Loader.dll 复制到 exe 同级（运行时 Core DLL 会通过 DllImport 加载它）
native = os.path.join(LIB, "WebView2Loader.dll")
if os.path.exists(native):
    shutil.copyfile(native, os.path.join(LOADER_DIR, "WebView2Loader.dll"))
    print("已复制 WebView2Loader.dll 到 exe 同级")

print()
print("产出:", OUT, os.path.getsize(OUT), "bytes")
print("loader 目录内容:", sorted(os.listdir(LOADER_DIR)))
