package com.ragpilot.core.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecursiveChunker} 单元测试（ADM-2.2）。
 */
class RecursiveChunkerTest {

    @Test
    @DisplayName("优先按段落分隔，边界与 FIXED 不同")
    void prefersParagraphBoundaries() {
        String text = "第一段内容够长了啊啊啊。\n\n第二段内容也够长了啊啊啊。\n\n第三段收尾。";
        List<String> recursive = new RecursiveChunker(24, 4).chunk(text);
        List<String> fixed = new FixedSizeChunker(24, 4).chunk(text);
        assertTrue(recursive.size() >= 1);
        // 同文不同策略：块边界应可区分（至少某一块内容不同或块数不同）
        assertTrue(!recursive.equals(fixed) || recursive.size() != fixed.size()
                || !recursive.getFirst().equals(fixed.getFirst()));
        assertNotEquals(fixed, recursive);
    }

    @Test
    @DisplayName("短于 size：单块返回")
    void shortTextSingleChunk() {
        assertEquals(List.of("短文"), new RecursiveChunker(100, 10).chunk("短文"));
    }

    @Test
    @DisplayName("自定义分隔符：只按句号切再合并")
    void customSeparators() {
        String text = "甲句。乙句。丙句。";
        List<String> parts = new RecursiveChunker(10, 2, List.of("。", "")).chunk(text);
        assertTrue(parts.size() >= 1);
        assertTrue(parts.stream().anyMatch(p -> p.contains("甲") || p.contains("乙")));
    }

    @Test
    @DisplayName("纯空白：空列表")
    void blankEmpty() {
        assertTrue(new RecursiveChunker(64, 8).chunk("   ").isEmpty());
    }
}
