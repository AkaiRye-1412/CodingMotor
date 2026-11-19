# PDF 中文字体

S4 报告（OpenHTMLtoPDF）使用 **Noto Sans SC** 渲染中文，**常规与粗体两份静态字重**。

## 已随包资源

| 文件 | 大小 | 用途 |
|---|---|---|
| `NotoSansSC-Regular.ttf` | ≈ 7.6 MB | 正文（`font-weight:400`） |
| `NotoSansSC-Bold.ttf` | ≈ 7.6 MB | 标题 / 表头 / 数值（`font-weight:700`） |

- 许可：SIL Open Font License 1.1（可商用、可嵌入、可随包分发）。
- 注册：`ReportPdfRenderer` 以字体族 `Noto Sans SC` 分别按 400 / 700 注册；模板 `report.html` 使用同名 `font-family`。
- 字体缺失时渲染器会记录告警并继续生成 PDF，但中文可能显示异常；正式交付前请确认这两个文件存在。

## 为什么是两份静态字体（不是一份可变字体）

最初的方案是单份 `NotoSansSC-VF.ttf`（可变字体）。有两个问题：

1. **粗体不生效**：只注册一份字体时，OpenHTMLtoPDF 找不到 700 字重会静默回退到同一份，`font-weight:700` 形同虚设，标题与表头和正文一样细。
2. **可变字体的默认实例是 wght=100（Thin）**：PDF 里的正文其实是极细体，放大看发虚。

因此改为从 VF 实例化出两份静态字体，再按 BMP 子集裁剪控制体积（合计 15.2 MB，比原 VF 的 17.7 MB 更小）。

## 重新生成（需要 Python + fonttools）

源文件另存于 `packaging/fonts-src/NotoSansSC-VF.ttf`（不打进 jar）：

```bash
cd packaging/fonts-src
python - <<'EOF'
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
for w, name in [(400, 'Regular'), (700, 'Bold')]:
    inst = instantiateVariableFont(TTFont('NotoSansSC-VF.ttf'), {'wght': w},
                                   updateFontNames=True, optimize=True)
    inst.save(f'NotoSansSC-{name}.ttf')
EOF

# 子集裁剪：拉丁 / 希腊 / 标点 / 圈码 / 数学符号 / CJK / 全角
python -m fontTools.subset NotoSansSC-Regular.ttf \
  --unicodes="U+0020-007E,U+00A0-00FF,U+0100-017F,U+02B0-02FF,U+0300-036F,U+0370-03FF,U+0400-04FF,U+2000-206F,U+2070-209F,U+20A0-20BF,U+2100-218F,U+2190-21FF,U+2200-22FF,U+2460-24FF,U+2500-257F,U+25A0-25FF,U+2600-26FF,U+3000-303F,U+4E00-9FFF,U+FE30-FE4F,U+FF00-FFEF" \
  --layout-features='*' --no-hinting --desubroutinize --drop-tables+=DSIG \
  --output-file=motorsizer-app/src/main/resources/fonts/NotoSansSC-Regular.ttf
# Bold 同理
```

> 子集里刻意保留 **整个 CJK 基本区**：报告的电机型号、参数名、工况说明都来自用户数据，无法预测用字。

## 验收

干净机器上生成的 PDF：中文无豆腐块（02 R-4 关闭条件）、标题与表头**明显粗于正文**、`PDFTextStripper` 抽出的文本可正常比对（连字符为 ASCII `-`，不会变成 U+2011）。
