# 黄金快照基线（Golden Snapshots）

本目录是 K1 计算内核迁移的**期望值基线**，对应设计 02 R-2「快照冻结」。

## 来源与生成

- 基线实现：[prototype/js/calc.js](../../../../../../prototype/js/calc.js)（**冻结，禁止修改**）
- 夹具电机：[prototype/js/data.js](../../../../../../prototype/js/data.js) 的演示库（仅作测试夹具，不进入产品）
- 生成脚本：[scripts/gen-golden.js](../../../../../../scripts/gen-golden.js)
- 重新生成：

```bash
node scripts/gen-golden.js
```

> 构建链与 Java 单测**不依赖 Node**：快照文件入 git，Java 只读 JSON。
> 只有在基线确需变更（并已评审通过）时才重新生成。

## 文件说明

| 文件 | 覆盖内容 |
|---|---|
| `fixtures.json` | 用例引用的演示电机原始对象（prototype 字段名） |
| `motion.json` | 点到点（梯形/三角形/默认/非法）、自定义反解、S 曲线平滑 |
| `theoretical.json` | 理论力/力矩：重力、阻力、停顿、平滑、旋转与音圈口径 |
| `screening.json` | 准入两条、余量、排序输入、缺项判定、速度跳过、margin=0 |
| `applied.json` | 应用值：电流、热校核、母线电压、供电结论、缺参数清单 |
| `sampling.json` | 曲线采样：无平滑、S 曲线、自定义三角，共 3 组 |
| `formatting.json` | 显示格式、单位换算、循环 RMS、红线校核、预设 τ、必填缺项 |

每个文件都带 `meta`：

- `baselineSha256`：`calc.js` 的 SHA-256，用于发现基线漂移；
- `fixturesSha256`：`data.js` 的 SHA-256；
- `tolerance`：Java 比对使用的相对容差（`1e-9`）；
- `frozenAt`：冻结日期（2026-11-18）。

## Java 比对规则（K1 实现时执行）

1. **数值**：`|actual - expected| <= 1e-9 * max(1, |expected|)`；`null` 必须对应 `null`。
2. **布尔与枚举**：完全相等。
3. **文案**（`message` / `reasons` / `note` / `supply.text`）：完全相等；若 Java 侧文案改动，需先评审并重新生成快照。
4. **数组**：长度与逐项数值同时校验；`sampling` 的 `t/v/a/f` 按索引逐项比对。
5. **失败结果**：`ok=false` 也算有效用例，必须比对错误码/文案与可行边界字段。

## 关键口径（迁移时不得想当然）

| 项 | 快照事实 | 说明 |
|---|---|---|
| 采样点数 | **241**（`i=0..240`，含两端） | 原型 `CURVE_SAMPLES=240` 是「等分区间数」，不是点数；与 04 API 文档写的「240 点」存在口径差异，见下 |
| 理论峰值 | 可能为**负值**（下坡工况） | 例如 `tilt270` 的 peak = -42.480；Java 不得擅自取绝对值 |
| 旋转重力 | 恒为 0 | 重力项只对直线生效 |
| 音圈 | 按 `kind='linear'` 计算，字段用 `Kf/Ke/R25` | 音圈是直线运动，速度/推力单位为 m/s、N |
| 旋转单位 | 峰值速度按 rpm、加速度按 rpm/s 展示 | 内部仍用 °/s 与 rad/s² 换算 |
| 缺项判定 | 依赖 `data.js` 的 `req` 标记 | Java 侧必须改用 K2 `required`，字段 key 映射需显式定义 |

## 已确认口径（2026-11-18）

| # | 决策 | 落地 |
|---|---|---|
| 1 | **采样固定 241 点**（240 等分区间，含 `t=0` 与 `t=T`） | 快照按 241 点冻结；04 文档已同步 |
| 2 | **理论峰值保留有符号语义** | Domain 不取绝对值；下坡工况 `peak = -42.480` 原样保留，展示层决定是否显示幅度 |
| 3 | **v1 S 曲线也均匀采样** | 不实现斜坡局部加密；后续版本再评估，届时需重定义点位分配 |
| 4 | **K1 内部使用 `double`** | 与 JS IEEE-754 语义一致，1e-9 容差可达；存储/API 边界再按 DECIMAL 处理 |
| 5 | **domain 测试期引入 Jackson** | `jackson-databind` 仅 test scope；主代码仍零框架依赖 |

## 使用限制

- 快照文件**禁止手工编辑**；任何改动都必须通过 `gen-golden.js` 重新生成并评审 diff。
- 新增工况请修改生成脚本中的用例矩阵，而不是直接改 JSON。
- `GoldenBaselineTest` 会校验 `calc.js` 哈希、关键锚点与采样点数；基线变化后必须同步更新测试常量。
