package com.ragpilot.eval.judge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2.7 验收：对 3 条已知好/坏样本，打分方向正确；并验证失败重试 1 次。
 *
 * <p>使用可复现的 stub {@link JudgeLlm}，不依赖真实本地模型（CI 默认可跑）。
 */
class JudgeDirectionTest {

    private static final String CONTEXT = """
            Spring Beans have a managed lifecycle inside the ApplicationContext.
            Stages: Instantiation, Populate properties, Aware callbacks,
            BeanPostProcessor before/after initialize, destroy.
            """;

    /**
     * Stub：根据答案内容返回固定 JSON——模拟 temperature=0 的本地 Judge 行为方向。
     * 好答案高分，幻觉/答非所问低分。
     */
    private final JudgeLlm directionalLlm = prompt -> {
        String lower = prompt.toLowerCase();
        // Faithfulness：答案声称被 K8s 管理 → 上下文不支持
        if (prompt.contains("Kubernetes 管理 Bean")) {
            return "{\"score\":0.1,\"reason\":\"hallucination not in context\"}";
        }
        // Faithfulness：忠实复述上下文
        if (prompt.contains("ApplicationContext") && prompt.contains("Instantiation")
                && prompt.contains("CONTEXT:")) {
            return "{\"score\":0.95,\"reason\":\"supported by context\"}";
        }
        // Relevancy：答非所问聊天气
        if (prompt.contains("今天天气晴") || lower.contains("weather")) {
            return "{\"score\":0.05,\"reason\":\"off-topic\"}";
        }
        // Relevancy：切题
        if (prompt.contains("生命周期") && prompt.contains("Instantiation")) {
            return "{\"score\":0.9,\"reason\":\"addresses the question\"}";
        }
        return "{\"score\":0.5,\"reason\":\"fallback\"}";
    };

    @Test
    void 三条样本打分方向正确() {
        FaithfulnessJudge faith = new FaithfulnessJudge(directionalLlm);
        RelevancyJudge relevancy = new RelevancyJudge(directionalLlm);

        // 样本 1：好 —— 忠实且切题
        String goodAnswer = "Bean 生命周期由 ApplicationContext 管理，包含 Instantiation 等阶段。";
        JudgeScore fGood = faith.score("Spring Bean 生命周期？", goodAnswer, List.of(CONTEXT));
        JudgeScore rGood = relevancy.score("Spring Bean 生命周期？", goodAnswer);

        // 样本 2：坏（幻觉）—— 忠实度应低
        String hallucinated = "Spring Bean 完全由 Kubernetes 管理 Bean，与容器无关。";
        JudgeScore fBad = faith.score("Spring Bean 生命周期？", hallucinated, List.of(CONTEXT));

        // 样本 3：坏（答非所问）—— 相关性应低
        String offTopic = "今天天气晴朗，适合出游。";
        JudgeScore rBad = relevancy.score("Spring Bean 生命周期？", offTopic);

        assertTrue(fGood.score() > fBad.score(),
                "忠实好样本应高于幻觉: " + fGood.score() + " vs " + fBad.score());
        assertTrue(rGood.score() > rBad.score(),
                "切题应高于答非所问: " + rGood.score() + " vs " + rBad.score());
        assertTrue(fGood.score() >= 0.8);
        assertTrue(fBad.score() <= 0.3);
        assertTrue(rBad.score() <= 0.3);
        assertEquals(FaithfulnessJudge.PROMPT_VERSION, fGood.promptVersion());
        assertEquals(RelevancyJudge.PROMPT_VERSION, rGood.promptVersion());
    }

    @Test
    void 解析失败会重试一次() {
        AtomicInteger calls = new AtomicInteger();
        JudgeLlm flaky = prompt -> {
            if (calls.getAndIncrement() == 0) {
                return "not-json";
            }
            return "{\"score\":0.8,\"reason\":\"ok after retry\"}";
        };
        JudgeScore score = new FaithfulnessJudge(flaky)
                .score("q", "a", List.of("ctx"));
        assertEquals(0.8, score.score(), 1e-9);
        assertEquals(2, calls.get());
    }

    @Test
    void 两次都失败则抛异常() {
        JudgeLlm alwaysBad = prompt -> "nope";
        assertThrows(IllegalStateException.class,
                () -> new RelevancyJudge(alwaysBad).score("q", "a"));
    }
}
