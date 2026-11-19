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
