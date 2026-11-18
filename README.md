# MotorSizer — 电机选型工具

把电机选型工程师在样册与 Excel 之间的换算过程，固化为一条可复算、可对照、可交付的本地流水线：

> 工况输入 → 运动规划 → 理论力/力矩 → 候选筛选 → 选定型号 → 实际应用值 → 曲线与报告

设计文档见 [doc/design/](doc/design/)，需求见 [doc/requirements.md](doc/requirements.md)，界面原型见 [prototype/](prototype/)（只读参照，不参与构建）。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3 · MyBatis-Plus · H2 文件模式（MODE=MySQL） |
| 计算内核 | `motorsizer-domain` 纯 Java，无 Spring / MyBatis 依赖 |
| 报告 | Thymeleaf + OpenHTMLtoPDF + Noto Sans SC |
| 前端 | Vue 3 + TypeScript + Vite 5 + Pinia + Element Plus + axios |
| 图表 | 自研 Canvas 组件 |
| 打包 | 单体 jar（内置前端产物）；后续 jpackage 绿色包 |

## 工程结构

```
motorsizer-domain/     纯 Java 计算内核：catalog(K2)、motion/physics/screening/sampling(K1)、units(K3)、model
motorsizer-app/        Spring Boot：controller / service / mapper / entity / dto / report / importing / config / common
frontend/              Vue3 + TS：api / types / stores / composables / views / panels / dialogs / charts / components / styles
scripts/               dev / build 脚本、schema.sql、gen-golden.js（黄金快照生成）
packaging/             jpackage 打包配置（占位）
doc/                   需求、设计、规范
prototype/             交互原型（基线参照）
```

## 本地开发

前置：JDK 17、Maven 3.9+、Node.js 20（或让 Maven 自动下载）。

```bash
# 一键起前后端（Windows）
scripts\dev.bat

# 或分别启动
mvn -pl motorsizer-app -am package -DskipTests -DskipFrontend=true
java -jar motorsizer-app/target/motorsizer-app-1.0.0-SNAPSHOT.jar

cd frontend
npm install
npm run dev        # http://localhost:5173，/api 代理到 127.0.0.1:8080
```

后端接口文档（开发期）：`http://127.0.0.1:8080/swagger-ui.html`
健康检查：`GET http://127.0.0.1:8080/api/health`

## 构建

```bash
# 前后端一起打包为单体 jar（首次会下载 node/npm）
mvn clean package

# 只构建后端（跳过前端）
mvn clean package -DskipFrontend=true
```

前端产物在构建时拷入 `target/classes/static`，jar 启动后同源访问，无 CORS。生产端口、自动开浏览器、单实例锁与 jpackage 见 [packaging/README.md](packaging/README.md)。

> `frontend/package-lock.json` 已生成并入库，Maven 使用 `npm ci` 复现依赖；构建用 Node 22 由 `frontend-maven-plugin` 自动下载。本地开发请使用 Node ≥22。

## 运行数据位置

- 数据库：`%APPDATA%\MotorSizer\data\motorsizer.mv.db`
- 日志：`%APPDATA%\MotorSizer\logs\motorsizer.log`
- 样册识别临时文件（待实现）：`%APPDATA%\MotorSizer\imports`

首次启动为空库，需先在电机库录入电机。

## 当前实现状态

| 模块 | 状态 |
|---|---|
| K2 字段模板 + A1 `GET /api/motor-templates` | ✅ 已实现 |
| S1 电机库 C1–C4 | ✅ 已实现（主表 + 三规格表同事务） |
| E1 健康检查 | ✅ 已实现 |
| 前端工程骨架（三列布局、面板占位、API/Store/类型层） | ✅ 已搭建 |
| 黄金快照基线（`scripts/gen-golden.js` + 7 份 JSON） | ✅ 已生成（2026-11-18 冻结） |
| K1 计算内核（规划 / 物理 / 应用值 / 准入 / 采样） | ✅ 已完整迁移，全部黄金用例逐项通过 |
| S3 `POST /api/calculations` | ✅ 已实现 B1 契约与 13 项接口测试 |
| 前端 M4 输入 / M6–M9 接线 | ✅ 已接入 B1：双列输入、6 卡片、候选过滤、参数表、应用值、两张曲线 |
| 前端 M10 录入表单（通道 1 手工录入） | ✅ 已实现：三类型切换保留值、必填前置、冷却标签联动、校验标红/聚焦/实时刷新、主键重复拒绝、音圈 g→kg 换算、来源信息；通道 2 样册识别禁用占位 |
| 样册识别 I1–I13 | 📐 设计稿 [doc/design/06.样册识别模块设计.md](doc/design/06.样册识别模块设计.md)（含 5 项待裁定）；包结构与依赖已就绪 |
| S4 报告 `POST /api/reports/pdf` | ✅ 已实现：Thymeleaf + OpenHTMLtoPDF + Noto Sans SC，5 项契约测试 + 端到端冒烟 |
| 启动器与打包（A-3/A-4/AR-11/R-3） | ✅ 已实现：单实例锁、自动空闲端口、jpackage 免安装绿色包；双击弹 **WebView2 原生窗口**（非浏览器标签页） |

> 计算规则只能存在于 `motorsizer-domain`（02 A-1）；前端只做输入规整（钳制、留空即自动、单位换算、显示精度）。

## 桌面打包

免安装绿色包（R-3 裁定）：`jpackage --type app-image` + jlink 裁剪 JRE + WebView2 原生窗口 loader。
双击 `MotorSizer.exe` 弹出**原生 WebView2 窗口**（Edge 内核、无地址栏、独立任务栏图标），不再是浏览器标签页。

```bash
# 1. 全量构建（前端 + 后端单体 jar）
scripts\build.bat

# 2. 准备 loader（首次）：下载 WebView2 DLL + 编译 loader
python packaging\windows\loader\download_nuget.py
python packaging\windows\loader\build_loader.py

# 3. 打包（需 JDK 17 含 jmods；本机默认探测 D:\develop\jdk-17）
packaging\windows\package.bat

# 4. 冒烟（可选）
python packaging\windows\smoke.py
```

产物：`packaging\windows\dist\MotorSizer\`，双击 `MotorSizer.exe` 弹原生窗口。首次启动自动建库（H2 文件位于 `%APPDATA%\MotorSizer\data\`）；重复双击受单实例锁保护；关闭窗口自动结束后端。`MotorSizerBackend.exe` 为后备入口（浏览器模式）。详见 [packaging/README.md](packaging/README.md)。
