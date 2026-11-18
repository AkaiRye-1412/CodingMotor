package com.motorsizer.app.common;

import java.util.List;

/**
 * 统一分页结构（04 §1.2）：页码从 1 起，totalPages = ceil(total / size)，total=0 时为 0。
 */
public record PageResult<T>(int page, int size, long total, int totalPages, List<T> items) {

    public static <T> PageResult<T> of(int page, int size, long total, List<T> items) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResult<>(page, size, total, totalPages, items);
    }
}
