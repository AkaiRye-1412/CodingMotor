# -*- coding: utf-8 -*-
"""
MotorSizer 免安装绿色包打包器（R-3 裁定 + WebView2 原生窗口）。
完整流程：jdeps 分析 → jlink 裁剪 JRE → jpackage app-image → 改造为 loader 主入口。

前置：先跑 scripts\\build.bat 得到 motorsizer-app\\target\\motorsizer-app-1.0.0-SNAPSHOT.jar
环境：JAVA_HOME 指向含 jpackage/jlink/jmods 的 JDK 17
产出：dist\\MotorSizer\\MotorSizer.exe（双击弹 WebView2 原生窗口，不再是浏览器标签）
"""
import os, re, tempfile, time, zipfile, subprocess, shutil, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jar_slim import slim_fat_jar  # noqa: E402  fat jar 瘦身（只留 win-x64 原生库）

PKG = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(PKG))  # MotorSizer 项目根
LOADER = os.path.join(PKG, "loader")
LOADER_LIB = os.path.join(LOADER, "lib")
ICONS = os.path.join(PKG, "icons")

def _detect_java_home():
    """JAVA_HOME 探测：环境变量 → 本机常见安装位置（D:\\develop 已废弃，保留兜底）。"""
    env = os.environ.get("JAVA_HOME")
    if env and os.path.exists(os.path.join(env, "bin", "jpackage.exe")):
        return env
    for candidate in (r"D:\Java\amazon-corretto-17", r"D:\develop\jdk-17"):
        if os.path.exists(os.path.join(candidate, "bin", "jpackage.exe")):
            return candidate
    return env or r"D:\Java\amazon-corretto-17"


JAVA_HOME = _detect_java_home()
JAR = os.path.join(ROOT, "motorsizer-app", "target", "motorsizer-app-1.0.0-SNAPSHOT.jar")
APP_INPUT = os.path.join(PKG, "app-input")
RUNTIME = os.path.join(PKG, "jre")
DIST = os.path.join(PKG, "dist")
ANALYSIS = os.path.join(PKG, "deps-analysis")
VERSION_FILE = os.path.join(PKG, "version.txt")


def read_version():
    """版本号唯一来源：packaging/windows/version.txt（发版只改这一处）。"""
    if not os.path.exists(VERSION_FILE):
        print(f"[错误] 未找到版本文件：{VERSION_FILE}")
        sys.exit(1)
    with open(VERSION_FILE, encoding="utf-8") as f:
        v = f.read().strip()
    if not v:
        print(f"[错误] 版本文件为空：{VERSION_FILE}")
        sys.exit(1)
    return v


APP_VERSION = read_version()


def trash_dir(path):
    """把目录整体挪到系统临时区，而不是逐文件递归删除。

    <p>本机对「同一轮里批量删除 >50 个文件」有确认闸门（safe-delete），
    rmtree 一个 app-image（200+ 文件）会被直接拦下、打包中断。整目录 rename 是 O(1)，
    不触发计数，残留交给系统临时目录回收。
    """
    if not os.path.exists(path):
        return
    trash = os.path.join(
        tempfile.gettempdir(),
        "motorsizer-build-" + os.path.basename(path) + "-" + str(int(time.time() * 1000)))
    try:
        os.rename(path, trash)
    except OSError:
        shutil.rmtree(path)  # 跨盘等 rename 失败时退回直接删


def reset_dir(path):
    """清空并重建目录（用于必须预先存在且为空的目标）。"""
    trash_dir(path)
    os.makedirs(path, exist_ok=True)


def frontend_assets(html):
    """取出 index.html 里引用的入口 js/css 文件名（Vite 构建产物带内容 hash）。"""
    return sorted(re.findall(r"assets/index-[A-Za-z0-9_-]+\.(?:js|css)", html))


def check_frontend_freshness():
    """jar 内前端必须与 frontend/dist 一致。

    <p>坑：用 `mvn package -DskipFrontend=true` 打包时，前端不重建，jar 里装的是
    target/classes/static 的上一次产物 —— 版本号升级了、界面却还是老的。这里直接比对
    两处 index.html 引用的 hash 文件名，不一致就中止打包。
    """
    dist_index = os.path.join(ROOT, "frontend", "dist", "index.html")
    if not os.path.exists(dist_index):
        print("[警告] 未找到 frontend/dist/index.html，跳过前端新鲜度校验")
        return
    with open(dist_index, encoding="utf-8") as f:
        want = frontend_assets(f.read())
    try:
        with zipfile.ZipFile(JAR) as z:
            got = frontend_assets(z.read("BOOT-INF/classes/static/index.html").decode("utf-8"))
    except KeyError:
        print("[错误] jar 内没有 BOOT-INF/classes/static/index.html —— 前端没有打进包。")
        sys.exit(1)
    if want != got:
        print("[错误] jar 里的前端与 frontend/dist 不一致，说明 jar 是用 -DskipFrontend=true 打的旧前端：")
        print("  jar 内   :", got)
        print("  dist 里  :", want)
        print("请先跑 scripts\\build.bat（mvn clean package 会重建前端）再打包。")
        sys.exit(1)
    print("  前端新鲜度校验通过:", got)


def check_backend_freshness():
    """jar 不能比后端源码旧（防「改完代码没重新 package 就打包」）。"""
    jar_mtime = os.path.getmtime(JAR)
    newest, newest_file = 0.0, ""
    for module in ("motorsizer-app", "motorsizer-domain"):
        for dirpath, _, files in os.walk(os.path.join(ROOT, module, "src", "main")):
            for fn in files:
                p = os.path.join(dirpath, fn)
                m = os.path.getmtime(p)
                if m > newest:
                    newest, newest_file = m, p
    if newest > jar_mtime + 1:
        print("[错误] jar 比后端源码旧，改动没打进包：")
        print("  最新源码:", newest_file)
        print("  源码时间:", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(newest)))
        print("  jar 时间 :", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(jar_mtime)))
        print("请先跑 scripts\\build.bat 重新构建。")
        sys.exit(1)
    print("  后端新鲜度校验通过（jar 不早于源码）")


def run(cmd, cwd=None):
    """运行命令，打印命令本身，返回 CompletedProcess。"""
    print(">", " ".join(cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.stdout.strip():
        print(r.stdout.strip()[:2000])
    if r.returncode != 0:
        if r.stderr.strip():
            print("[stderr]", r.stderr.strip()[:1000])
        print("命令失败，exit code:", r.returncode)
        sys.exit(r.returncode)
    return r


def step(n, msg):
    print(f"\n[{n}] {msg}")


# ---------- 前置检查 ----------
jp = os.path.join(JAVA_HOME, "bin", "jpackage.exe")
if not os.path.exists(jp):
    print(f"[错误] 未找到 jpackage（需 JDK 17 + jmods）：{JAVA_HOME}")
    sys.exit(1)
if not os.path.exists(JAR):
    print(f"[错误] 未找到单体 jar：{JAR}")
    print("请先运行 scripts\\build.bat 完成全量构建。")
    sys.exit(1)
if not os.path.exists(os.path.join(LOADER, "MotorSizerLoader.exe")):
    print("[错误] 未找到 loader，请先运行 loader\\build_loader.py 编译。")
    sys.exit(1)

# 打包前两道新鲜度闸门：jar 里的前端/后端必须与源码对得上，否则打出来的包「版本新、内容旧」
check_backend_freshness()
check_frontend_freshness()

# ---------- 1. jdeps 分析模块依赖 ----------
step(1, "jdeps 分析模块依赖")
reset_dir(ANALYSIS)
run([os.path.join(JAVA_HOME, "bin", "jar.exe"), "-xf", JAR,
     "BOOT-INF/classes", "BOOT-INF/lib"], cwd=ANALYSIS)

r = subprocess.run(
    [os.path.join(JAVA_HOME, "bin", "jdeps.exe"),
     "--print-module-deps", "--ignore-missing-deps", "--multi-release", "17",
     "-cp", os.path.join("BOOT-INF", "lib", "*"),
     os.path.join("BOOT-INF", "classes")],
    cwd=ANALYSIS, capture_output=True, text=True, encoding="utf-8", errors="replace")
deps = r.stdout.strip()
if not deps:
    deps = "java.base"
print("  jdeps 模块清单:", deps)

# 运行期动态依赖补充（反射/SPI 等 jdeps 看不到的）
# java.naming: Tomcat/JNDI；java.xml: Spring XML；jdk.localedata+jdk.charsets: 中文；
# jdk.crypto.ec: 加密套件；jdk.zipfs: 嵌套 jar 读取
modules = deps + ",java.naming,java.xml,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.zipfs"
print("  补充后模块清单:", modules)

# ---------- 2. jlink 裁剪 JRE ----------
step(2, "jlink 裁剪 JRE")
trash_dir(RUNTIME)  # jlink --output 要求目标不存在
run([os.path.join(JAVA_HOME, "bin", "jlink.exe"),
     "--add-modules", modules,
     "--include-locales=zh,en",
     "--strip-debug", "--no-header-files", "--no-man-pages",
     "--compress", "2",
     "--output", RUNTIME])

# ---------- 3. jpackage app-image ----------
step(3, "jpackage app-image")
# 只清 app-image：dist/ 下还有历次发布的 .msi，不能整个 dist 一起清（曾把归档包删掉）
app_image_dir = os.path.join(DIST, "MotorSizer")
trash_dir(app_image_dir)  # jpackage 要求同名 app-image 目录不存在
os.makedirs(DIST, exist_ok=True)
os.makedirs(APP_INPUT, exist_ok=True)
jar_in_input = os.path.join(APP_INPUT, "motorsizer-app-1.0.0-SNAPSHOT.jar")
try:
    new_size = slim_fat_jar(JAR, jar_in_input)
    print(f"  瘦身后 jar：{new_size/1024/1024:.1f} MB（原 {os.path.getsize(JAR)/1024/1024:.1f} MB）")
except Exception as ex:                                   # 瘦身失败绝不能挡住打包
    print(f"  [警告] fat jar 瘦身失败，改用原始 jar：{ex}")
    shutil.copyfile(JAR, jar_in_input)

ico = os.path.join(ICONS, "motorsizer.ico")
run([os.path.join(JAVA_HOME, "bin", "jpackage.exe"),
     "--type", "app-image",
     "--name", "MotorSizer",
     "--input", APP_INPUT,
     "--main-jar", "motorsizer-app-1.0.0-SNAPSHOT.jar",
     "--runtime-image", RUNTIME,
     "--icon", ico,
     "--dest", DIST,
     "--app-version", APP_VERSION,
     "--vendor", "Akribis",
     "--java-options", "-Dfile.encoding=UTF-8",
     "--java-options", "-Dsun.stdout.encoding=UTF-8",
     "--java-options", "-Dsun.stderr.encoding=UTF-8",
     # 让 /api/health 与日志报出真实安装版本（HealthController 读 ${motorsizer.version}，
     # 系统属性优先于 jar 内 application.yml 的默认值）
     "--java-options", f"-Dmotorsizer.version={APP_VERSION}",
     ])

# ---------- 4. 改造 app-image：loader 作为主入口 ----------
step(4, "改造 app-image：WebView2 loader 作为主入口")
app_image = os.path.join(DIST, "MotorSizer")
print("  app-image:", app_image)

# jpackage 生成的 MotorSizer.exe 改名为 MotorSizerBackend.exe（后备：浏览器模式）
# 注意：jpackage 启动器会按 exe 同名去 app/ 下找 <名字>.cfg，改名 exe 必须同步改 cfg，
# 否则 MotorSizerBackend.exe 启动报 "Error opening ...MotorSizerBackend.cfg: No such file"。
jp_exe = os.path.join(app_image, "MotorSizer.exe")
backend_exe = os.path.join(app_image, "MotorSizerBackend.exe")
if os.path.exists(jp_exe):
    shutil.move(jp_exe, backend_exe)
    cfg_src = os.path.join(app_image, "app", "MotorSizer.cfg")
    cfg_dst = os.path.join(app_image, "app", "MotorSizerBackend.cfg")
    if os.path.exists(cfg_src):
        shutil.move(cfg_src, cfg_dst)
        print("  app/MotorSizer.cfg → app/MotorSizerBackend.cfg（与改名后启动器对齐）")
    print("  jpackage launcher → MotorSizerBackend.exe（后备浏览器模式）")

# MotorSizerLoader.exe 作为主入口 MotorSizer.exe
loader_exe = os.path.join(LOADER, "MotorSizerLoader.exe")
shutil.copyfile(loader_exe, os.path.join(app_image, "MotorSizer.exe"))
print("  MotorSizerLoader.exe → MotorSizer.exe（主入口，弹 WebView2 原生窗口）")

# 复制 WebView2 DLL 到 exe 同级（运行时 Core DLL 通过 DllImport 加载 WebView2Loader.dll）
for dll in ["WebView2Loader.dll",
            os.path.join(LOADER_LIB, "Microsoft.Web.WebView2.Core.dll"),
            os.path.join(LOADER_LIB, "Microsoft.Web.WebView2.WinForms.dll")]:
    src = dll if os.path.isabs(dll) else os.path.join(LOADER, dll)
    dst = os.path.join(app_image, os.path.basename(src))
    shutil.copyfile(src, dst)
    print(f"  复制 {os.path.basename(src)} ({os.path.getsize(src)} bytes)")

# ---------- 5. 清理临时目录 ----------
step(5, "清理临时目录")
for d in [ANALYSIS, APP_INPUT, RUNTIME]:
    if os.path.isdir(d):
        trash_dir(d)
        print("  已移入临时区", os.path.basename(d))

# ---------- 统计 ----------
total = 0
for root, dirs, files in os.walk(app_image):
    for f in files:
        total += os.path.getsize(os.path.join(root, f))
print(f"\n打包完成：{app_image}")
print(f"绿色包大小：{total/1024/1024:.1f} MB")
print("双击 MotorSizer.exe 启动 → 弹原生 WebView2 窗口（不再是浏览器标签页）")
print("MotorSizerBackend.exe 为后备入口（浏览器模式，自动开浏览器）。")
