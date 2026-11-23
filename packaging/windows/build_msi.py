# -*- coding: utf-8 -*-
"""
MotorSizer 安装包（.msi）打包器。

流程：复用 package_app.py 产出 app-image（含 WebView2 loader 主入口）
      → WiX heat 自动收集 app-image 全部文件为 Component
      → 写主 .wxs（安装目录 / 快捷方式 / 卸载）→ candle + light 打成 .msi

前置：
  1. 先跑 scripts\\build.bat 得到 motorsizer-app-1.0.0-SNAPSHOT.jar
  2. 先跑 loader\\build_loader.py 得到 MotorSizerLoader.exe
  3. 本机已装 WiX Toolset（默认在 D:\\WiX\\3.14.1）

产出：dist\\MotorSizer-<version>.msi（版本号见 packaging\\windows\\version.txt）
"""
import os
import shutil
import subprocess
import sys
import time


# WiX 主模板：定义安装目录、Feature、快捷方式、卸载、UIRef
MAIN_WXS_TEMPLATE = r'''<?xml version="1.0" encoding="utf-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*"
           Name="{product_name}"
           Language="2052"
           Manufacturer="{vendor}"
           Version="{version}"
           UpgradeCode="{upgrade_code}">

    <Package InstallerVersion="500"
             Compressed="yes"
             InstallScope="perMachine"
             Platform="x64"
             Description="MotorSizer 电机选型工具"
             Manufacturer="{vendor}"
             Languages="2052" />

    <!-- 主升级：同 UpgradeCode 的旧版本先卸载 -->
    <MajorUpgrade DowngradeErrorMessage="已安装更新的版本，无法降级安装。"
                  AllowSameVersionUpgrades="yes" />

    <MediaTemplate EmbedCab="yes" />

    <!-- 安装目录：Program Files\MotorSizer -->
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="ProgramFiles64Folder">
        <Directory Id="INSTALLFOLDER" Name="{product_name}" />
      </Directory>
      <!-- 桌面快捷方式 -->
      <Directory Id="DesktopFolder" />
      <!-- 开始菜单 -->
      <Directory Id="ProgramMenuFolder">
        <Directory Id="ApplicationProgramsFolder" Name="{product_name}" />
      </Directory>
    </Directory>

    <!-- 文件组件由 heat 生成，这里只声明 ComponentGroup 引用 -->
    <Feature Id="MainFeature" Title="{product_name}" Level="1">
      <ComponentGroupRef Id="AppFilesGroup" />
      <ComponentRef Id="AppShortcutComponent" />
    </Feature>

    <!-- 快捷方式组件：指向安装目录里的 MotorSizer.exe（loader 主入口） -->
    <DirectoryRef Id="ApplicationProgramsFolder">
      <Component Id="AppShortcutComponent" Guid="*">
        <Shortcut Id="StartMenuShortcut"
                  Name="{product_name}"
                  Description="MotorSizer 电机选型工具"
                  Target="[INSTALLFOLDER]MotorSizer.exe"
                  WorkingDirectory="INSTALLFOLDER" />
        <Shortcut Id="DesktopShortcut"
                  Directory="DesktopFolder"
                  Name="{product_name}"
                  Description="MotorSizer 电机选型工具"
                  Target="[INSTALLFOLDER]MotorSizer.exe"
                  WorkingDirectory="INSTALLFOLDER" />
        <RemoveFolder Id="RemoveApplicationProgramsFolder"
                      Directory="ApplicationProgramsFolder"
                      On="uninstall" />
        <RegistryValue Root="HKCU"
                       Key="Software\{vendor}\{product_name}"
                       Name="installed"
                       Type="integer"
                       Value="1"
                       KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <Icon Id="app.ico" SourceFile="$(var.IconDir)\motorsizer.ico" />

    <!-- 标准 UI：安装向导 -->
    <UIRef Id="WixUI_InstallDir" />
    <Property Id="WIXUI_INSTALLDIR" Value="INSTALLFOLDER" />

    <UI>
      <Publish Dialog="WelcomeDlg" Control="Next" Event="NewDialog" Value="InstallDirDlg" Order="2">1</Publish>
      <Publish Dialog="InstallDirDlg" Control="Back" Event="NewDialog" Value="WelcomeDlg" Order="2">1</Publish>
    </UI>

    <!-- 应用图标显示 -->
    <Property Id="ARPPRODUCTICON" Value="app.ico" />
  </Product>
</Wix>
'''

PKG = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(PKG))
DIST = os.path.join(PKG, "dist")
APP_IMAGE = os.path.join(DIST, "MotorSizer")
WIX = os.environ.get("WIX") or r"D:\WiX\3.14.1"
CANDLE = os.path.join(WIX, "candle.exe")
LIGHT = os.path.join(WIX, "light.exe")
HEAT = os.path.join(WIX, "heat.exe")

APP_VERSION_FILE = os.path.join(PKG, "version.txt")


def read_version():
    """版本号唯一来源：packaging/windows/version.txt（与 package_app.py 同一份）。"""
    if not os.path.exists(APP_VERSION_FILE):
        print(f"[错误] 未找到版本文件：{APP_VERSION_FILE}")
        sys.exit(1)
    with open(APP_VERSION_FILE, encoding="utf-8") as f:
        v = f.read().strip()
    if not v:
        print(f"[错误] 版本文件为空：{APP_VERSION_FILE}")
        sys.exit(1)
    return v


APP_VERSION = read_version()
UPGRADE_CODE = "8C7A9D2E-6F1B-4A3E-9B5C-1D2E3F4A5B6C"  # 固定，用于版本升级识别
PRODUCT_NAME = "MotorSizer"
VENDOR = "Akribis"

WORK = os.path.join(PKG, "msi-work")  # WiX 中间产物目录


def run(cmd, cwd=None):
    print(">", " ".join(cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.stdout.strip():
        print(r.stdout.strip()[:3000])
    if r.returncode != 0:
        if r.stderr.strip():
            print("[stderr]", r.stderr.strip()[:2000])
        print("命令失败，exit code:", r.returncode)
        sys.exit(r.returncode)
    return r


def step(n, msg):
    print(f"\n[{n}] {msg}")


# ---------- 前置检查 ----------
for exe in (CANDLE, LIGHT, HEAT):
    if not os.path.exists(exe):
        print(f"[错误] 未找到 WiX 工具：{exe}\n请安装 WiX Toolset 或设置环境变量 WIX 指向其安装目录。")
        sys.exit(1)
if not os.path.exists(APP_IMAGE):
    print(f"[错误] 未找到 app-image：{APP_IMAGE}")
    print("请先运行 package_app.py 产出绿色包。")
    sys.exit(1)

# 防「拿旧 app-image 重新 link 出一个新版本号的 MSI」：jar 时间与版本号都要对得上
app_jar = os.path.join(APP_IMAGE, "app", "motorsizer-app-1.0.0-SNAPSHOT.jar")
target_jar = os.path.join(ROOT, "motorsizer-app", "target", "motorsizer-app-1.0.0-SNAPSHOT.jar")
if not os.path.exists(app_jar):
    print(f"[错误] app-image 里没有 jar：{app_jar}")
    sys.exit(1)
if os.path.exists(target_jar) and os.path.getmtime(app_jar) < os.path.getmtime(target_jar) - 1:
    print("[错误] app-image 里的 jar 比 target 的旧，说明没有重新跑 package_app.py：")
    print("  app-image:", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(app_jar))))
    print("  target   :", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(target_jar))))
    sys.exit(1)
jp_xml = os.path.join(APP_IMAGE, "app", ".jpackage.xml")
if os.path.exists(jp_xml):
    with open(jp_xml, encoding="utf-8") as f:
        if f"<app-version>{APP_VERSION}</app-version>" not in f.read():
            print(f"[错误] app-image 的版本与 version.txt（{APP_VERSION}）不一致，请重跑 package_app.py。")
            sys.exit(1)
    print(f"  app-image 版本校验通过: {APP_VERSION}")

# ---------- 0. 准备工作目录 ----------
step(0, "准备 WiX 中间产物目录")
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)

# ---------- 1. heat 收集 app-image 全部文件 ----------
step(1, "heat 收集 app-image 文件清单")
frag_wxs = os.path.join(WORK, "FilesFragment.wxs")
run([HEAT, "dir", APP_IMAGE,
     "-gg",                      # 生成 GUID
     "-sfrag",                   # 每个文件独立 Fragment
     "-srd",                     # 不递归目录（目录结构由主 wxs 的 Directory 定义）
     "-scom", "-sreg", "-svb6",  # 跳过 COM/注册表/VB6 提取
     "-dr", "INSTALLFOLDER",     # 根目录引用 INSTALLFOLDER
     "-cg", "AppFilesGroup",     # ComponentGroup 名
     "-var", "var.AppImageDir",  # 用变量引用源目录（light -b 绑定）
     "-out", frag_wxs])
print("  生成", frag_wxs)

# heat 会把 app-image 顶层目录当作 INSTALLFOLDER 下的一个同名子目录引用，需要修正：
# 用 sed 把 <DirectoryRef Id="MotorSizer"> 改成 <DirectoryRef Id="INSTALLFOLDER">
# 实际上 -srd 已让 heat 直接输出 INSTALLFOLDER 下的文件，这里检查产物内容。
with open(frag_wxs, encoding="utf-8") as f:
    frag = f.read()

# heat 生成的 Component 里 Source 形如 "SourceDir\MotorSizer\file"（相对 app-image 父目录），
# 我们在 light 时用 -b dist 绑定 SourceDir，保证能找到文件。
print("  片段行数:", frag.count("\n"))

# ---------- 2. 生成主 wxs ----------
step(2, "生成主 wxs")
main_wxs = os.path.join(WORK, "MotorSizer.wxs")
wxs_content = MAIN_WXS_TEMPLATE.format(
    product_name=PRODUCT_NAME,
    vendor=VENDOR,
    version=APP_VERSION,
    upgrade_code=UPGRADE_CODE,
)
with open(main_wxs, "w", encoding="utf-8") as f:
    f.write(wxs_content)
print("  生成", main_wxs)

# ---------- 3. candle 编译 ----------
step(3, "candle 编译 wxs → wixobj")
obj_main = os.path.join(WORK, "MotorSizer.wixobj")
obj_frag = os.path.join(WORK, "FilesFragment.wixobj")
icons_dir = os.path.join(PKG, "icons")
run([CANDLE, "-nologo", "-arch", "x64",
     f"-dAppImageDir={APP_IMAGE}",
     f"-dIconDir={icons_dir}",
     "-out", obj_main, main_wxs])
run([CANDLE, "-nologo", "-arch", "x64",
     f"-dAppImageDir={APP_IMAGE}",
     f"-dIconDir={icons_dir}",
     "-out", obj_frag, frag_wxs])

# ---------- 4. light 链接 ----------
step(4, "light 链接 wixobj → msi")
msi_out = os.path.join(DIST, f"{PRODUCT_NAME}-{APP_VERSION}.msi")
run([LIGHT, "-nologo", "-ext", "WixUIExtension",
     "-b", DIST,                      # SourceDir 绑定到 dist，让 heat 的相对路径可解析
     "-cultures:zh-CN;en-US",
     "-out", msi_out,
     obj_main, obj_frag])

# ---------- 5. 收尾 ----------
step(5, "完成")
size = os.path.getsize(msi_out)
print(f"\n安装包已生成：{msi_out}")
print(f"大小：{size/1024/1024:.1f} MB")
print(f"目标电脑双击 {PRODUCT_NAME}-{APP_VERSION}.msi 即可安装（需 64 位 Win10/11）。")
print("安装后入口：开始菜单 / 桌面快捷方式 → MotorSizer（弹原生 WebView2 窗口）。")
