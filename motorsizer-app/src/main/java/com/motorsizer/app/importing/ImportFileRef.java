package com.motorsizer.app.importing;

/** 上传文件登记。 */
public record ImportFileRef(
        String fileId,
        String name,
        String kind,        // pdf
        int pages,
        long sizeBytes) {
}
