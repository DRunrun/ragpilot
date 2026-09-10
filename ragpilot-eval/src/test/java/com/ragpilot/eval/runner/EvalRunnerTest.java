package com.ragpilot.eval.runner;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.eval.dataset.GoldenQuestion;
import com.ragpilot.eval.dataset.GoldenSetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalRunner / GoldenSetLoader 验收：假检索跑通并产出可序列化报告结构。
 */
class EvalRunnerTest {

    @Test
    void 加载v1黄金集恰好50题() {
        List<GoldenQuestion> golden = new GoldenSetLoader().loadClasspath("golden/v1.0.jsonl");
        assertEquals(50, golden.size());
        assertTrue(golden.stream().filter(GoldenQuestion::shouldRefuse).count() >= 8);
    }

    @Test
    void 指定模式跑全量产出报告() {
        List<GoldenQuestion> golden = List.of(
                new GoldenQuestion("t1", "Bean 生命周期？", "ref",
                        List.of("spring-bean-lifecycle"), List.of(),
                        List.of("lifecycle"), "easy"),
                new GoldenQuestion("t2", "今天天气？", "",
                        List.of(), List.of(),
                        List.of("refusal"), "easy")
        );

        EvalRunner runner = new EvalRunner(
                (q, topK, mode) -> {
                    assertEquals(RetrievalMode.HYBRID, mode);
                    if (q.contains("天气")) {
                        return List.of();
                    }
                    TextChunk chunk = new TextChunk(
                            "spring-bean-lifecycle#0",
                            "spring-bean-lifecycle",
                            "lifecycle text",
                            0,
                            Map.of()
                    );
                    return List.of(new RetrievedChunk(chunk, 0.9, 1, RetrievedChunk.Channel.RRF));
                },
                (q, hits) -> hits.isEmpty() ? Optional.empty() : Optional.of("答案"),
                null
        );

        EvalRunner.EvalReport report = runner.run(RetrievalMode.HYBRID, golden, 5);
        assertEquals("HYBRID", report.mode());
        assertEquals(2, report.questionCount());
        assertEquals(1.0, report.recallAtK(), 1e-9);
        assertEquals(1.0, report.mrr(), 1e-9);
        assertEquals(1.0, report.refusalAccuracy(), 1e-9);
        assertNotNull(report.cases());
        assertEquals(2, report.cases().size());
    }
}
