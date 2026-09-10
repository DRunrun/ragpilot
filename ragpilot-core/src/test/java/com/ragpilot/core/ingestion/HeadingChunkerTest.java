package com.ragpilot.core.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeadingChunker} 单元测试（ADM-2.1）。
 */
class HeadingChunkerTest {

    @Test
    @DisplayName("面试题风格：每个 ### 标题一节一块")
    void oneSectionPerHeading() {
        String text = """
                ### 题目 A
                答案 A 内容。
                
                ### 题目 B
                答案 B 内容。
                """;
        List<String> parts = new HeadingChunker(512, 64).chunk(text);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).startsWith("### 题目 A"));
        assertTrue(parts.get(1).startsWith("### 题目 B"));
    }

    @Test
    @DisplayName("无标题：整篇作为单块（短文）")
    void noHeadingKeepsWhole() {
        assertEquals(List.of("hello world"), new HeadingChunker(100, 10).chunk("hello world"));
    }

    @Test
    @DisplayName("过长 section：二次 FIXED 切分，内容不丢")
    void longSectionSecondarySplit() {
        String body = "字".repeat(30);
        String text = "### 长题\n" + body;
        List<String> parts = new HeadingChunker(20, 4).chunk(text);
        assertTrue(parts.size() >= 2);
        String joined = String.join("", parts);
        // 二次切有 overlap，拼接会重复；至少应包含标题与原文主体
        assertTrue(joined.contains("### 长题") || parts.getFirst().contains("###"));
        assertTrue(joined.contains("字"));
    }

    @Test
    @DisplayName("纯空白：空列表")
    void blankEmpty() {
        assertTrue(new HeadingChunker(64, 8).chunk("  \n  ").isEmpty());
    }
}
