package com.ragpilot.core.ingestion;

/**
 * 分块策略枚举：决定 {@link Chunker} 如何切文本。
 *
 * <p>链路位置：ingestion 分块步骤的策略开关（Admin 覆盖层 {@code ragpilot.chunk.strategy}）。
 *
 * <p>为什么单独枚举：预览 API、运行时装配、消融对比都要用同一套名字，避免散落字符串。
 */
public enum ChunkStrategy {

    /** 固定字符窗口 + overlap（M1 默认）。 */
    FIXED,

    /** 按 Markdown H1～H3 切；过长段落再按 size 二次切。 */
    HEADING,

    /** 按分隔符优先级递归切，再拼到接近 size。 */
    RECURSIVE;

    /**
     * 解析策略名；非法或空时默认 {@link #FIXED}。
     *
     * @param raw 策略字符串，允许大小写混写
     */
    public static ChunkStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIXED;
        }
        try {
            return ChunkStrategy.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FIXED;
        }
    }
}
