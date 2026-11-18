package com.motorsizer.app.importing;

import com.motorsizer.app.dto.MotorUpsertRequest;

import java.util.List;
import java.util.Map;

/**
 * 识别草稿（06 §4 drafts[]）。
 *
 * <p>{@code request} 结构对齐 {@link MotorUpsertRequest}（{@code entryMethod=识别草稿}），
 * 提交时直接交给 {@code MotorService.create}，复用 C3 校验与事务。
 * {@code rawFields} 保留样册原始值与文本，供人工核对；{@code issues} 由校验回填。
 *
 * @param draftId    草稿 id（d1, d2...）
 * @param pageId     来源页（需求 6.4：逐项标注来源页）
 * @param sourcePage 来源页人类可读标注（如「第 2 页」）
 * @param confidence 置信度 = 命中字段数 / 应命中字段数（Q5：< 0.6 标红）
 * @param request    待入库请求体
 * @param rawFields  原始字段：key → {value, unit, text}
 * @param issues     校验问题：{field, reason}
 * @param checked    是否勾选提交（I12 只提交勾选项）
 */
public record Draft(
        String draftId,
        String pageId,
        String sourcePage,
        double confidence,
        MotorUpsertRequest request,
        Map<String, RawField> rawFields,
        List<DraftIssue> issues,
        boolean checked) {

    /** 样册原始值快照。 */
    public record RawField(String value, String unit, String text) {
    }
}
