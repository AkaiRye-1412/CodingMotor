# 打包（待实现）

设计裁定（02 R-3）：交付**免安装绿色包** —— `jpackage --type app-image` 产出目录
（内含 jlink 裁剪 JRE + 主 exe），双击即用、免管理员权限、不写注册表。

## 前置条件

1. 先完成全量构建：`scripts/build.bat`（或 `mvn clean package`），得到内置前端资源的单体 jar。
2. 准备 Noto Sans SC 字体（见 `motorsizer-app/src/main/resources/fonts/README.md`）。
3. 若纳入样册识别的 OCR，需额外打包 Tess4J 原生库与 `tessdata_best/chi_sim`。

## 计划产物

- `packaging/windows/`：jpackage 参数、应用图标、启动器配置。
- 启动参数：仅监听 `127.0.0.1`，自动选空闲端口并打开浏览器；单实例锁（A-3/A-4）。
- 数据位置：`%APPDATA%\MotorSizer\`（数据库、日志、导入临时文件）。

> 当前仅为目录占位；jpackage 脚本、图标与 JRE 模块清单在进入交付阶段时补齐。
