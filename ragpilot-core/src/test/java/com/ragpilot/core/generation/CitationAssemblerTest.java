package com.ragpilot.core.generation;

import com.ragpilot.core.domain.Citation;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CitationAssembler} 单元测试。
 *
 * <p>验收口径（execution-plan F1.12）：
 * 答案含 [1][3]、citations 只有 2 条 → 校验产出 1 条 warning。
 */
class CitationAssemblerTest {

    private final CitationAssembler assembler = new CitationAssembler();

    private static RetrievedChunk hit(String chunkId, String content) {
        TextChunk chunk = new TextChunk(chunkId, "doc-a", content, 0, Map.of());
        return new RetrievedChunk(chunk, 0.9, 1, RetrievedChunk.Channel.VECTOR);
    }

    @Test
    @DisplayName("组装：编号从 1 递增，snippet 来自块内容")
    void assemblesNumberedCitations() {
        List<Citation> citations = assembler.assemble(List.of(
                hit("doc-a#0", "第一块内容"),
                hit("doc-a#1", "第二块内容")));

        assertEquals(2, citations.size());
        assertEquals(1, citations.get(0).index());
        assertEquals(2, citations.get(1).index());
        assertEquals("doc-a#0", citations.get(0).chunkId());
        assertEquals("第一块内容", citations.get(0).snippet());
    }

    @Test
    @DisplayName("组装：超长 snippet 截断，空命中返回空列表")
    void truncatesSnippetAndHandlesEmpty() {
        String longContent = "长".repeat(300);
        List<Citation> citations = assembler.assemble(List.of(hit("doc-a#0", longContent)));
        assertTrue(citations.get(0).snippet().endsWith("…"));
        assertTrue(citations.get(0).snippet().length() <= 201);

        assertTrue(assembler.assemble(List.of()).isEmpty());
    }

    @Test
    @DisplayName("F1.12 验收：答案 [1][3] 但只有 2 条引用 → 恰好 1 条越界 warning")
    void outOfRangeMarkProducesOneWarning() {
        List<Citation> citations = assembler.assemble(List.of(
                hit("doc-a#0", "一"), hit("doc-a#1", "二")));

        List<String> warnings = assembler.validate("答案正文 [1] 还有 [3]", citations);

        assertEquals(1, warnings.size(), "只有 [3] 越界，应恰好 1 条 warning");
        assertTrue(warnings.get(0).contains("[3]"));
    }

    @Test
    @DisplayName("校验：编号全部合法或答案无标记 → 无 warning")
    void validMarksProduceNoWarning() {
        List<Citation> citations = assembler.assemble(List.of(hit("doc-a#0", "一")));

        assertTrue(assembler.validate("见 [1]", citations).isEmpty());
        assertTrue(assembler.validate("没有任何标记", citations).isEmpty());
        assertTrue(assembler.validate("", citations).isEmpty());
    }
}
