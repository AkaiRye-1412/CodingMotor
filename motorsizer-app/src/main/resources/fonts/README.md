# PDF 中文字体

S4 报告（OpenHTMLtoPDF）使用 **Noto Sans SC** 渲染中文。

## 已随包资源

- 文件：`NotoSansSC-VF.ttf`（Noto Sans SC 可变字体，17 MB）。
- 许可：SIL Open Font License 1.1（可商用、可嵌入、可随包分发）。
- 注册：`ReportPdfRenderer` 以字体族 `Noto Sans SC` 注册；模板 `report.html` 使用同名 `font-family`。
- 字体缺失时渲染器会记录告警并继续生成 PDF，但中文可能显示异常；正式交付前请确认该文件存在。

## 验收

干净机器上生成的 PDF 中文无豆腐块（02 R-4 关闭条件）。

> 若后续替换为 Noto Sans SC Regular 静态字体，只需替换文件名并更新 `ReportPdfRenderer` 的资源路径。
