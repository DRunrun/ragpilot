package com.ragpilot.core.generation;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptBuilder} 单元测试。
 *
 * <p>验收口径（execution-plan F1.10）：
 * 有命中 → Prompt 含 [1]；空命中 → Optional.empty()（不生成 prompt）。
 */
class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    private static RetrievedChunk hit(String chunkId, String docId, String content) {
        TextChunk chunk = new TextChunk(chunkId, docId, content, 0, Map.of("title", docId));
        return new RetrievedChunk(chunk, 0.9, 1, RetrievedChunk.Channel.VECTOR);
    }

    @Test
    @DisplayName("有命中：Prompt 含 [1] 编号、来源信息与问题原文")
    void buildsPromptWithNumberedContext() {
        Optional<String> prompt = builder.build(
                "Spring Bean 的生命周期是什么？",
                List.of(hit("doc-a#0", "doc-a", "Bean 生命周期包括实例化、初始化、销毁。")));

        assertTrue(prompt.isPresent(), "有命中必须生成 Prompt");
        String text = prompt.get();
        assertTrue(text.contains("[1] source=doc-a chunkId=doc-a#0"), "上下文块必须带编号与来源");
        assertTrue(text.contains("Bean 生命周期包括实例化、初始化、销毁。"), "块内容必须进上下文");
        assertTrue(text.contains("Spring Bean 的生命周期是什么？"), "问题必须进 Prompt");
        assertTrue(text.contains("仅基于"), "约束要素不能丢");
    }

    @Test
    @DisplayName("多条命中：编号从 1 递增")
    void numbersMultipleHitsFromOne() {
        Optional<String> prompt = builder.build("问题",
                List.of(hit("d#0", "d", "第一块"), hit("d#1", "d", "第二块")));

        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().contains("[1] source=d chunkId=d#0"));
        assertTrue(prompt.get().contains("[2] source=d chunkId=d#1"));
    }

    @Test
    @DisplayName("空命中：返回 Optional.empty()，堵死无上下文生成的路径")
    void emptyHitsYieldNoPrompt() {
        assertEquals(Optional.empty(), builder.build("任何问题", List.of()));
        assertEquals(Optional.empty(), builder.build("任何问题", null));
    }

    @Test
    @DisplayName("空白问题与非法模板：构造/调用期快速失败")
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> builder.build("  ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PromptBuilder("没有占位符的模板"));
    }
}
