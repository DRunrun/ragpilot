package com.ragpilot.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3.1 验收：Mock 工具验证多步终止与超时熔断。
 */
class ReActAgentTest {

    @Test
    void 多步调用后finish终止() {
        AtomicInteger decides = new AtomicInteger();
        ReActAgent.DecisionMaker planner = (q, history) -> {
            int n = decides.incrementAndGet();
            if (n == 1) {
                return new ReActAgent.Decision("先查知识库", "search", "Bean 生命周期");
            }
            if (n == 2) {
                return new ReActAgent.Decision("已有证据", "finish", "八个阶段……");
            }
            throw new IllegalStateException("不应再决策");
        };
        List<String> toolCalls = new ArrayList<>();
        ReActAgent.ToolInvoker tools = (name, input) -> {
            toolCalls.add(name + ":" + input);
            return "命中：Instantiation → … → destroy";
        };

        ReActAgent agent = new ReActAgent(planner, tools, 5, Duration.ofSeconds(2));
        ReActAgent.AgentResult result = agent.run("Spring Bean 生命周期？");

        assertEquals("八个阶段……", result.finalAnswer());
        assertEquals(2, result.steps().size());
        assertEquals(1, toolCalls.size());
        assertFalse(result.timedOut());
        assertFalse(result.maxStepsReached());
        assertTrue(result.steps().get(1).isFinish());
    }

    @Test
    void 单步超时熔断() {
        ReActAgent.DecisionMaker planner = (q, history) ->
                new ReActAgent.Decision("调慢工具", "slow", "x");
        ReActAgent.ToolInvoker tools = (name, input) -> {
            Thread.sleep(500);
            return "too late";
        };

        ReActAgent agent = new ReActAgent(planner, tools, 5, Duration.ofMillis(50));
        ReActAgent.AgentResult result = agent.run("timeout?");

        assertTrue(result.timedOut());
        assertEquals(1, result.steps().size());
        assertTrue(result.steps().get(0).timedOut());
        assertTrue(result.steps().get(0).failed());
        assertEquals("", result.finalAnswer());
    }

    @Test
    void 工具失败重试一次后成功() {
        AtomicInteger invokes = new AtomicInteger();
        ReActAgent.DecisionMaker planner = (q, history) -> {
            if (history.isEmpty()) {
                return new ReActAgent.Decision("试工具", "flaky", "q");
            }
            return new ReActAgent.Decision("够了", "finish", "ok");
        };
        ReActAgent.ToolInvoker tools = (name, input) -> {
            if (invokes.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }
            return "recovered";
        };

        ReActAgent.AgentResult result = new ReActAgent(planner, tools, 5, Duration.ofSeconds(2))
                .run("retry?");

        assertEquals(2, invokes.get());
        assertTrue(result.steps().get(0).retried());
        assertFalse(result.steps().get(0).failed());
        assertEquals("recovered", result.steps().get(0).observation());
        assertEquals("ok", result.finalAnswer());
    }

    @Test
    void 触达maxSteps未finish() {
        ReActAgent.DecisionMaker planner = (q, history) ->
                new ReActAgent.Decision("再搜", "search", "x");
        ReActAgent.ToolInvoker tools = (name, input) -> "obs";

        ReActAgent.AgentResult result = new ReActAgent(planner, tools, 3, Duration.ofSeconds(2))
                .run("loop?");

        assertTrue(result.maxStepsReached());
        assertEquals(3, result.steps().size());
        assertEquals("", result.finalAnswer());
    }
}
