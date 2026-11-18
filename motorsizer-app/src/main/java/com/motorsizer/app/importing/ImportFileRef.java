package com.motorsizer.app.importing;

/** 上传文件登记（06 §4 files[]）。 */
public record ImportFileRef(
        String fileId,
        String name,
        String kind,        // pdf / image
        int pages) {        // PDF 页数；图片固定 1
}
