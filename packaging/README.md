# 打包（Windows 免安装绿色包 + WebView2 原生窗口）

设计裁定（02 R-3）：交付**免安装绿色包** —— `jpackage --type app-image` 产出目录
（内含 jlink 裁剪 JRE），解压双击即用、免管理员权限、不写注册表。

双击 `MotorSizer.exe` 弹出的是**原生 WebView2 窗口**（Edge 内核，无地址栏），
不再是浏览器标签页。Win10/11 自带 WebView2 Runtime，无需额外安装。

## 产物结构

```
packaging/windows/
├── package_app.py      打包执行器（jdeps→jlink→jpackage→loader 改造）
├── package.bat         一键打包入口（设 JAVA_HOME + 调 package_app.py）
├── smoke.py            打包后冒烟验证
├── make_ico.py         ICO 拼装（配合 MakeIcon.java 的 PNG）
├── MakeIcon.java       应用图标源（Graphics2D 绘制）
├── icons/
│   ├── motorsizer.ico  应用图标（16–256 七尺寸）
│   └── icon-*.png      各尺寸 PNG
├── loader/             WebView2 原生窗口 loader（C# .NET 4.8）
│   ├── MotorSizerLoader.cs    loader 源码
│   ├── build_loader.py        编译脚本（csc → MotorSizerLoader.exe）
│   ├── download_nuget.py      下载 WebView2 NuGet 包
│   ├── lib/                   WebView2 DLL（NuGet 解压，不入库）
│   └── MotorSizerLoader.exe   编译产物（30KB，依赖系统 .NET 4.8）
└── dist/MotorSizer/     最终绿色包
    ├── MotorSizer.exe          ← 主入口（loader，弹 WebView2 窗口）
    ├── MotorSizerBackend.exe   ← 后备入口（jpackage launcher，浏览器模式）
    ├── WebView2Loader.dll      WebView2 native loader
    ├── Microsoft.Web.WebView2.Core.dll
    ├── Microsoft.Web.WebView2.WinForms.dll
    ├── app/                    jar + cfg
    └── runtime/                jlink JRE
```

## 使用

1. 全量构建：`scripts\build.bat`，得到单体 jar。
2. 准备 loader（首次）：`python packaging\windows\loader\download_nuget.py` 下载 WebView2 DLL → `python packaging\windows\loader\build_loader.py` 编译 loader。
3. 打包：`packaging\windows\package.bat`（需 JDK 17 含 jmods；本机默认探测 `D:\develop\jdk-17`）。
4. 冒烟（可选）：`python packaging\windows\smoke.py`，加 `--kill` 则验证完自动结束进程。
5. 交付：把 `dist\MotorSizer\` 整个目录压缩分发，用户解压双击 `MotorSizer.exe`。

## 运行形态

- **原生窗口**：双击 `MotorSizer.exe` → loader 启动后端 → 弹 WebView2 原生窗口（无地址栏，
  独立任务栏图标），关闭窗口自动结束后端。`MotorSizerBackend.exe` 为后备入口（浏览器模式）。
- 仅监听 `127.0.0.1`；8080 空闲即用，被占用自动改随机端口（AR-11），实际端口写入
  `%APPDATA%\MotorSizer\app.port`。
- 单实例锁（A-4）：loader 用 Mutex + 后端文件锁双重保护，重复双击不再起新进程。
- 数据：`%APPDATA%\MotorSizer\data\motorsizer.mv.db`；日志：`%APPDATA%\MotorSizer\logs\`
  （`motorsizer.log` 后端、`loader.log` loader 诊断、`backend.out` 后端 stdout）。

## 打包口径

- jlink 模块清单 = `jdeps` 静态分析 + 运行期动态补充（`java.naming`/`java.xml` Tomcat/Spring
  反射依赖、`jdk.localedata`/`jdk.charsets` 中文、`jdk.crypto.ec`、`jdk.zipfs`）。
- WebView2 loader 用 .NET Framework 4.8（Win10/11 自带，不打包 runtime），仅增 ~1MB
  （loader exe 30KB + 3 个 DLL ~0.9MB）。
- 样册识别含 OCR（PP-OCRv6 small + ONNX Runtime，全离线），体积代价两处：
  模型 30MB（`resources/ocr/`，无法再压）；`onnxruntime` jar 89MB（解开 290MB）。
  后者由 `jar_slim.py` 在打包时裁剪：只留 `win-x64/*.dll`，剔掉 linux/osx 全部原生库
  与所有 `.pdb`/`.dSYM` 调试符号（仅 `onnxruntime.pdb` 就占压缩后 58MB）。
  fat jar 181.8MB → 97.5MB，省 84.3MB。原 `target/` jar 不动，其他平台仍可构建。

## MSI 安装包（企业分发）

产物 `dist\MotorSizer-<版本>.msi`（perMachine 装到 `C:\Program Files\MotorSizer`，
同 UpgradeCode、每版新 ProductCode，靠 `<MajorUpgrade>` 覆盖升级）。

**版本号唯一来源**：`packaging/windows/version.txt`。`package_app.py` 用它做
`--app-version` 与 `-Dmotorsizer.version`，`build_msi.py` 用它写 MSI 的 Product Version。
发版只改这一个文件。

流程：

```
scripts\build.bat                     # 后端 + 前端 + 合进 jar
packaging\windows\package.bat         # 绿色包 app-image（含 jlink / jpackage / loader）
python packaging\windows\build_msi.py # → dist\MotorSizer-<版本>.msi
```

### 升级与卸载（终端用户口径）

- **升级不用先卸载旧版**：直接双击新 msi，`<MajorUpgrade>` 会把同 UpgradeCode 的旧版本
  自动卸掉再装新版（`RemoveExistingProducts` 默认排在 `InstallValidate` 之后，即「先完整
  卸载旧版 → 再装新版」；中途失败重跑一次 msi 即可恢复）。
- **同版本号重装也允许**（`AllowSameVersionUpgrades="yes"`，每版 ProductCode 都不同），
  用于修复装坏的环境。
- **降级被拦**：装低于当前的版本会提示「已安装更新的版本，无法降级安装。」，必须先手动卸载。
- **必须先退出程序**：jar 与 `runtime\bin\java.exe` 被占用时安装会失败/回滚；正常关窗口
  会在 `Run()` 的 `finally` 里 `Kill` 后端，异常退出残留的 `java.exe` 需在任务管理器结束。
- **需要管理员**：perMachine 装到 `C:\Program Files\MotorSizer`，会弹 UAC。
- **数据不动**：H2 库在 `%APPDATA%\MotorSizer\data`、WebView 缓存在
  `%APPDATA%\MotorSizer\webview-data`，都不在安装目录里，升级与卸载都不会碰。
- **另一套安装器不受管**：老机器上若残留 `AkribisMotorSizerSetup`（不是本 MSI，没有
  UpgradeCode 关系），不会被自动卸载，需手动删掉，以免快捷方式与端口冲突。

### 打包前的两道新鲜度闸门

`package_app.py` 会主动拦住两类「版本号升了、内容是旧的」：

1. **后端新鲜度**：`target` 里的 jar 不能比 `motorsizer-app` / `motorsizer-domain` 的
   `src/main` 源码旧 —— 防「改完代码没重新 package 就打包」。
2. **前端新鲜度**：jar 内 `BOOT-INF/classes/static/index.html` 引用的 `index-<hash>.js`
   必须与 `frontend/dist/index.html` 一致 —— 防用 `-DskipFrontend=true` 打包时，
   把 `target/classes/static` 里上一次的前端产物带进包。

`build_msi.py` 再校验一次：app-image 里的 jar 不早于 target jar，且
`app\.jpackage.xml` 的版本与 `version.txt` 一致。

### 版本在哪儿看

- 窗口标题：`MotorSizer  电机选型工具  v<版本>`（loader 读 `app\.jpackage.xml`）。
- `GET http://127.0.0.1:<port>/api/health` 的 `data.version`：loader 用
  `-Dmotorsizer.version=<版本>` 传给后端（**loader 是直接 `java -jar` 起的后端，
  不走 jpackage 的 `MotorSizerBackend.cfg`，所以参数必须自己传**）。

### 坑：卸载旧版、装新版，界面和功能还是旧的

现象：ARP/安装列表里版本号已经变成新版，程序打开却还是上一版的界面。
三层原因叠加，缺一不可：

1. 后端静态资源只返回 `Last-Modified`、**没有 `Cache-Control`**，Chromium 按
   「启发式缓存」（freshness ≈ 距 Last-Modified 时长的 10%）直接命中本地缓存。
2. WebView2 的用户数据目录固定在 `%APPDATA%\MotorSizer\webview-data`，**卸载不清理**；
   而后端端口常年就是 8080，index.html 的 URL 与旧版逐字相同 → 缓存必然命中。
3. loader 关掉了浏览器快捷键（`AreBrowserAcceleratorKeysEnabled=false`，含 F5/Ctrl+F5），
   用户连强制刷新都做不到。

1.1.4 起的修复：

- 后端 `application.yml` 加 `spring.web.resources.cache.cachecontrol.no-cache /
  must-revalidate`，每次请求都向服务器校验（命中时走 304，开销可忽略）。
- loader 启动 URL 追加 `?v=<启动时间戳>`，每次启动都换 URL，彻底绕开残留缓存；
  页面内 js/css 带内容 hash，仍可正常缓存。
- 窗口标题带版本，装错版本一眼可见。

**应急办法**（已装老版本、暂时不升级）：关掉程序 → 删除
`%APPDATA%\MotorSizer\webview-data` 整个目录 → 重新打开。数据在 `data\` 下，不受影响。
