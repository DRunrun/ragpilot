package com.ragpilot.core.agent.tools;

import com.ragpilot.core.agent.KnowledgeFirstDecisionMaker;
import com.ragpilot.core.agent.ReActAgent;
import com.ragpilot.core.agent.ToolRegistry;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.Retriever;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3.2 验收：Agent 能自主决定调用 knowledge_search。
 */
class KnowledgeSearchToolTest {

    @Test
    void Agent自主先检索再作答() {
        AtomicInteger retrieveCount = new AtomicInteger(0);
        Retriever retriever = (query, topK) -> {
            retrieveCount.incrementAndGet();
            TextChunk chunk = new TextChunk(
                    "spring-bean-lifecycle#0",
                    "spring-bean-lifecycle",
                    "Instantiation → Populate → destroy",
                    0,
                    Map.of()
            );
            return List.of(new RetrievedChunk(chunk, 0.9, 1, RetrievedChunk.Channel.VECTOR));
        };

        KnowledgeSearchTool search = new KnowledgeSearchTool(retriever, 5);
        ToolRegistry registry = new ToolRegistry(List.of(search));
        AtomicBoolean synthesized = new AtomicBoolean(false);
        // 单测不连 LLM：合成器断言收到结构化证据（首次检索的块直接复用，不再二次检索）
        ReActAgent agent = new ReActAgent(
                new KnowledgeFirstDecisionMaker(registry, (q, evidence) -> {
                    synthesized.set(true);
                    assertEquals(1, evidence.size(), "应复用首次检索命中的 1 个块");
                    assertTrue(evidence.get(0).chunk().content().contains("Instantiation"),
                            "证据应是 knowledge_search 的命中块而非文本");
                    return "模型整理：Instantiation → Populate → destroy";
                }),
                registry,
                5,
                Duration.ofSeconds(2)
        );

        ReActAgent.AgentResult result = agent.run("Spring Bean 生命周期有哪些阶段？");

        assertEquals(1, retrieveCount.get(), "全链路只应检索一次（消除二次检索）");
        assertTrue(synthesized.get(), "检索后应调用答案合成器（生产侧接 LLM）");
        assertEquals(2, result.steps().size());
        assertEquals(KnowledgeSearchTool.NAME, result.steps().get(0).action());
        assertTrue(result.steps().get(1).isFinish());
        assertTrue(result.finalAnswer().startsWith("模型整理："));
        assertTrue(result.finalAnswer().contains("Instantiation"));
        assertFalse(result.finalAnswer().startsWith("基于检索结果："));
        assertFalse(result.timedOut());
    }

    @Test
    void 工具暴露name与schema() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool((q, k) -> List.of(), 3);
        assertEquals(KnowledgeSearchTool.NAME, tool.name());
        assertTrue(tool.description().toLowerCase().contains("knowledge"));
        assertTrue(tool.inputSchema().contains("query"));
    }
}
