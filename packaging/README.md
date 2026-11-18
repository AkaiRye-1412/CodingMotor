# 打包（Windows 免安装绿色包）

设计裁定（02 R-3）：交付**免安装绿色包** —— `jpackage --type app-image` 产出目录
（内含 jlink 裁剪 JRE + 主 exe），解压双击即用、免管理员权限、不写注册表。

## 产物结构

```
packaging/windows/
├── package.bat          一键打包脚本（jdeps → jlink → jpackage）
├── smoke.py             打包后冒烟验证（启动 exe → 健康检查 → 可选收尾）
├── MakeIcon.java        应用图标源（Graphics2D 绘制，重新生成用）
├── icons/
│   ├── motorsizer.ico   应用图标（16–256 七尺寸）
│   └── icon-*.png       各尺寸 PNG
├── app-input/           jpackage 输入（构建时拷入单体 jar）
├── jre/                 jlink 裁剪 JRE（构建产物）
├── deps-analysis/       jdeps 分析用解包（构建产物）
└── dist/MotorSizer/     最终绿色包（MotorSizer.exe + runtime）
```

## 使用

1. 全量构建：`scripts\build.bat`（或 `mvn clean package`），得到单体 jar。
2. 打包：`packaging\windows\package.bat`（需 JDK 17 含 jmods；本机默认探测 `D:\develop\jdk-17`）。
3. 冒烟（可选）：`python packaging\windows\smoke.py`，加 `--kill` 则验证完自动结束进程。
4. 交付：把 `dist\MotorSizer\` 整个目录压缩分发，用户解压双击 `MotorSizer.exe`。

## 运行形态（02 §2.3.3）

- 仅监听 `127.0.0.1`；8080 空闲即用，被占用自动改随机端口（AR-11），实际端口写入
  `%APPDATA%\MotorSizer\app.lock`。
- 单实例锁（A-4）：重复双击不再起新进程，而是打开既有实例界面。
- 启动就绪后自动打开默认浏览器（`-Dmotorsizer.openBrowser=false` 可关闭）。
- 数据：`%APPDATA%\MotorSizer\data\motorsizer.mv.db`；日志：`%APPDATA%\MotorSizer\logs\`。

## 打包口径

- jlink 模块清单 = `jdeps` 静态分析 + 运行期动态补充（`jdk.localedata`/`jdk.charsets`
  中文环境、`jdk.crypto.ec`、`jdk.zipfs`）；`--include-locales=zh,en` 控制体积。
- OCR（样册识别 I1–I13）尚未纳入：纳入后需追加 Tess4J 原生库与 `tessdata`，并重新评估体积（150–180MB）。
- 无控制台窗口（不用 `--win-console`）：诊断看日志文件；需要现场排查时可临时改用
  `runtime\bin\java -jar app\motorsizer-app-1.0.0-SNAPSHOT.jar` 观察输出。
