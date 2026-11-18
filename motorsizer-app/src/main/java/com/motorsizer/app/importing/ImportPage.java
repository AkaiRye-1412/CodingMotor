package com.motorsizer.app.importing;

/** 页面 = 解析单元（06 §4 pages[]）。 */
public record ImportPage(
        String pageId,
        String fileId,
        int pageNumber,
        PageStatus status,
        String message,
        int textLineCount) {

    public static ImportPage pending(String pageId, String fileId, int pageNumber) {
        return new ImportPage(pageId, fileId, pageNumber, PageStatus.PENDING, null, 0);
    }
}
