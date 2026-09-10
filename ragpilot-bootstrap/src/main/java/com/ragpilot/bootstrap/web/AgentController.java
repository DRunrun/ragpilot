package com.ragpilot.bootstrap.web;

import com.ragpilot.core.agent.AgentStep;
import com.ragpilot.core.agent.ReActAgent;
import com.ragpilot.ops.trace.TraceIds;
import com.ragpilot.ops.trace.TraceRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 问答入口：一句话触发 ReAct 多步（查知识库 → LLM 组织答案）。
 *
 * <p>链路位置：M3 Demo API；每步写入 {@link TraceRecorder}，可用 {@code /api/v1/trace/{id}} 回看。
 * <p>finish 前会经 {@code AnswerSynthesizer} 调用与 /ask 相同的 Prompt/Generator，不再直接返回 HITS 原文。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final ReActAgent reActAgent;
    private final TraceRecorder traceRecorder;

    public AgentController(ReActAgent reActAgent, TraceRecorder traceRecorder) {
        this.reActAgent = reActAgent;
        this.traceRecorder = traceRecorder;
    }

    /**
     * 同步跑完 Agent，返回答案 + 步骤 + traceId。
     *
     * <p>示例：{@code POST /api/v1/agent/ask {"question":"Spring Bean 生命周期？"}}
     */
    @PostMapping("/agent/ask")
    public Mono<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        String question = body == null ? null : body.get("question");
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        String traceId = TraceIds.newId();
        return Mono.fromCallable(() -> {
                    traceRecorder.start(traceId, "agent");
                    ReActAgent.AgentResult result = reActAgent.run(question.strip());
                    for (AgentStep step : result.steps()) {
                        traceRecorder.recordAgentStep(
                                traceId,
                                step.index(),
                                step.thought(),
                                step.action(),
                                step.actionInput(),
                                step.observation(),
                                step.durationMs(),
                                step.failed(),
                                step.timedOut()
                        );
                    }
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("traceId", traceId);
                    resp.put("answer", result.finalAnswer());
                    resp.put("timedOut", result.timedOut());
                    resp.put("maxStepsReached", result.maxStepsReached());
                    resp.put("steps", result.steps().stream().map(this::toView).toList());
                    return resp;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> toView(AgentStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", step.index());
        m.put("thought", step.thought());
        m.put("action", step.action());
        m.put("actionInput", step.actionInput());
        m.put("observation", step.observation());
        m.put("durationMs", step.durationMs());
        m.put("failed", step.failed());
        m.put("timedOut", step.timedOut());
        return m;
    }
}
