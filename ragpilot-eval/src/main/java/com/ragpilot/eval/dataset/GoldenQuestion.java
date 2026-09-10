package com.ragpilot.eval.dataset;

import java.util.List;

/**
 * 黄金集单题：评测 Runner 的输入行。
 *
 * <p>链路位置：评测数据层；字段与 {@code docs/evaluation.md} Schema 对齐。
 *
 * @param id                 稳定题号
 * @param question           用户问题
 * @param referenceAnswer    参考答案（Judge / 人工对照，可空）
 * @param expectedDocIds     期望命中的文档 ID；空表示不评检索或应拒答
 * @param expectedChunkIds   更严的块级期望（可空）
 * @param tags               标签（含 refusal / reviewed 等）
 * @param difficulty         easy/medium/hard
 */
public record GoldenQuestion(
        String id,
        String question,
        String referenceAnswer,
        List<String> expectedDocIds,
        List<String> expectedChunkIds,
        List<String> tags,
        String difficulty
) {

    public GoldenQuestion {
        referenceAnswer = referenceAnswer == null ? "" : referenceAnswer;
        expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        tags = tags == null ? List.of() : List.copyOf(tags);
        difficulty = difficulty == null ? "" : difficulty;
    }

    /** 是否标注为应拒答题（tags 含 refusal）。 */
    public boolean shouldRefuse() {
        return tags.stream().anyMatch(t -> "refusal".equalsIgnoreCase(t));
    }
}
