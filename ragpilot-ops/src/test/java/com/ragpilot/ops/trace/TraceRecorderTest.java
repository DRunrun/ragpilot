package com.ragpilot.ops.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceRecorder 单测：追加事件后可按 ID 取回。
 */
class TraceRecorderTest {

    @Test
    void 记录后可查询完整事件() {
        TraceRecorder recorder = new TraceRecorder();
        recorder.start("t1", "agent");
        recorder.recordAgentStep("t1", 1, "think", "knowledge_search", "q", "hits…", 12, false, false);
        recorder.record("t1", new TraceEvent(
                "token", System.currentTimeMillis(), 0, "", "", "", 10, 20, Map.of()));

        TraceRecorder.TraceRecord snap = recorder.find("t1").orElseThrow().snapshot();
        assertEquals(2, snap.events().size());
        assertEquals("agent_step", snap.events().get(0).type());
        assertEquals("knowledge_search", snap.events().get(0).tool());
        assertEquals(10, snap.events().get(1).promptTokens());
        assertTrue(TraceRecorder.summarize("a".repeat(600)).endsWith("…"));
    }
}
