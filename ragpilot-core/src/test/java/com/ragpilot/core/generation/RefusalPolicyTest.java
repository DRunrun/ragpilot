package com.ragpilot.core.generation;

import com.ragpilot.core.domain.AskResult;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefusalPolicy} 单元测试。
 *
 * <p>验收口径（execution-plan F1.13）：空库（无命中）提问 →
 * refused=true 且 reason=NO_EVIDENCE；配合编排层保证此时不调用 LLM。
 */
class RefusalPolicyTest {

    private static final String TRACE = "trace-1";

    private static RetrievedChunk anyHit() {
        TextChunk chunk = new TextChunk("d#0", "d", "内容", 0, Map.of());
        return new RetrievedChunk(chunk, 0.8, 1, RetrievedChunk.Channel.VECTOR);
    }

    @Test
    @DisplayName("无命中：拒答 NO_EVIDENCE，不产生正常答案")
    void emptyHitsRefuseWithNoEvidence() {
        RefusalPolicy policy = new RefusalPolicy(true);

        Optional<AskResult> result = policy.checkBeforeGeneration(List.of(), TRACE);

        assertTrue(result.isPresent(), "空命中必须拒答");
        assertTrue(result.get().refused());
        assertEquals(RefusalPolicy.NO_EVIDENCE, result.get().refuseReason());
        assertTrue(result.get().citations().isEmpty());
        // null 入参等同无命中
        assertTrue(policy.checkBeforeGeneration(null, TRACE).isPresent());
    }

    @Test
    @DisplayName("有命中：放行生成；开关关闭时空命中也放行（仅调试用）")
    void hitsPassThroughAndSwitchWorks() {
        RefusalPolicy policy = new RefusalPolicy(true);
        assertTrue(policy.checkBeforeGeneration(List.of(anyHit()), TRACE).isEmpty());

        RefusalPolicy disabled = new RefusalPolicy(false);
        assertTrue(disabled.checkBeforeGeneration(List.of(), TRACE).isEmpty());
    }

    @Test
    @DisplayName("空答案：拒答 EMPTY_ANSWER；正常答案放行")
    void blankAnswerRefused() {
        RefusalPolicy policy = new RefusalPolicy(true);

        Optional<AskResult> refused = policy.checkAfterGeneration("   ", TRACE);
        assertTrue(refused.isPresent());
        assertEquals(RefusalPolicy.EMPTY_ANSWER, refused.get().refuseReason());

        assertTrue(policy.checkAfterGeneration("正常答案 [1]", TRACE).isEmpty());
    }
}
