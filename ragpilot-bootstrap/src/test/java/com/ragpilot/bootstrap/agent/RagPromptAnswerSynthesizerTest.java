package com.ragpilot.bootstrap.agent;

import com.ragpilot.core.agent.KnowledgeFirstDecisionMaker;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 合成器：有命中时必须走 Generator，而不是原样返回 HITS。
 */
class RagPromptAnswerSynthesizerTest {

    @Test
    void 有命中时调用生成器拼答案() {
        AtomicBoolean generated = new AtomicBoolean(false);
        TextChunk chunk = new TextChunk(
                "doc#0", "doc", "RAG 三步：准备数据、查资料、生成答案", 0, Map.of());
        RetrievedChunk hit = new RetrievedChunk(chunk, 0.9, 1, RetrievedChunk.Channel.VECTOR);

        Generator generator = prompt -> {
            generated.set(true);
            assertTrue(prompt.contains("准备数据"), "Prompt 应含检索上下文");
            return Flux.just("完整流程：", "准备→检索→生成")
                    .map(text -> (GenerationEvent) new GenerationEvent.Token(text));
        };

        // 新契约：合成器不再持有 Retriever，直接消费首次检索传回的块
        RagPromptAnswerSynthesizer synth = new RagPromptAnswerSynthesizer(
                new PromptBuilder(),
                generator
        );

        String answer = synth.synthesize("RAG的完整流程是怎么样的", List.of(hit));
        assertTrue(generated.get());
        assertEquals("完整流程：准备→检索→生成", answer);
    }

    @Test
    void 无命中拒答() {
        Generator never = prompt -> Flux.just("不应调用")
                .map(text -> (GenerationEvent) new GenerationEvent.Token(text));
        RagPromptAnswerSynthesizer synth = new RagPromptAnswerSynthesizer(
                new PromptBuilder(),
                never
        );
        assertEquals(
                KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER,
                synth.synthesize("q", List.of())
        );
    }
}
