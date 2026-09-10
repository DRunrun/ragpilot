package com.ragpilot.core.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FixedSizeChunker} 单元测试。
 *
 * <p>纯逻辑测试，不依赖网络、数据库和大模型，必须能离线秒级跑完。
 * 分块策略是 RAG 质量的第一道闸门，改动这里必须先让这些用例通过，
 * 并按 AGENTS.md 要求在提交里说明对评测的影响。
 */
class FixedSizeChunkerTest {

    @Test
    @DisplayName("正常文本：按 size 切分，相邻块重叠 overlap 个字符")
    void chunksWithOverlap() {
        FixedSizeChunker chunker = new FixedSizeChunker(5, 2);
        List<String> parts = chunker.chunk("abcdefghij");

        // 手工推演：[0,5)=abcde → 回退 2 → [3,8)=defgh → 回退 2 → [6,10)=ghij（末块不足 5 按实际长度收口）
        assertEquals(List.of("abcde", "defgh", "ghij"), parts);
    }

    @Test
    @DisplayName("纯空白文本：返回空列表，避免产生空向量污染库")
    void emptyInput() {
        assertTrue(new FixedSizeChunker(8, 2).chunk("   ").isEmpty());
    }

    @Test
    @DisplayName("短于一块的文本：整体作为单块返回，不做补齐")
    void textShorterThanChunkSize() {
        assertEquals(List.of("abc"), new FixedSizeChunker(10, 2).chunk("abc"));
    }

    @Test
    @DisplayName("非法参数：overlap >= size 会导致窗口无法前进，必须构造期就失败")
    void illegalOverlapRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FixedSizeChunker(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new FixedSizeChunker(0, 0));
    }
}
