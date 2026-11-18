package com.motorsizer.app.importing.recognize;

/** 一页提取出的文本（按视觉行，已去空行与首尾空白）。 */
public record PageText(java.util.List<String> lines) {

    public int lineCount() {
        return lines.size();
    }
}
