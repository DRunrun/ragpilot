package com.ragpilot.bootstrap.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.admin.ChatSessionService;
import com.ragpilot.bootstrap.admin.KnowledgeFilterResolver;
import com.ragpilot.bootstrap.resilience.GenerationDegradedException;
import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.core.domain.AskRequest;
import com.ragpilot.core.domain.AskResult;
import com.ragpilot.core.domain.Citation;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.generation.CitationAssembler;
import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.core.generation.RefusalPolicy;
import com.ragpilot.core.retrieval.QueryRewriter;
import com.ragpilot.ops.token.TokenMeter;
import com.ragpilot.ops.trace.TraceEvent;
import com.ragpilot.ops.trace.TraceIds;
import com.ragpilot.ops.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 问答接口：RAG 主链路的 HTTP 入口，SSE 流式输出。
 *
 * <p>F4.1：done / Trace 记录 {@code promptVersion}。
 * <p>F4.2：生成超时/失败降级为 refused + done，检索异常同理，避免 500 白屏。
 */
@RestController
@RequestMapping("/api/v1")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final ModeAwareRetriever retriever;
    private final KnowledgeFilterResolver knowledgeFilterResolver;
    private final ChatSessionService chatSessionService;
    private final PromptBuilder promptBuilder;
    private final Generator generator;
    private final CitationAssembler citationAssembler;
    private final RefusalPolicy refusalPolicy;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traceRecorder;
    private final TokenMeter tokenMeter;
    private final QueryRewriter queryRewriter;
    private final boolean queryRewriteEnabled;
    private final int queryRewriteMaxTurns;
    private final int defaultTopK;

    public AskController(
            ModeAwareRetriever retriever,
            KnowledgeFilterResolver knowledgeFilterResolver,
            ChatSessionService chatSessionService,
            PromptBuilder promptBuilder,
            Generator generator,
            CitationAssembler citationAssembler,
            RefusalPolicy refusalPolicy,
            ObjectMapper objectMapper,
            TraceRecorder traceRecorder,
            TokenMeter tokenMeter,
            QueryRewriter queryRewriter,
            com.ragpilot.bootstrap.config.RagPilotProperties props
    ) {
        this.retriever = retriever;
        this.knowledgeFilterResolver = knowledgeFilterResolver;
        this.chatSessionService = chatSessionService;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.citationAssembler = citationAssembler;
        this.refusalPolicy = refusalPolicy;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.tokenMeter = tokenMeter;
        this.queryRewriter = queryRewriter;
        this.queryRewriteEnabled = props.queryRewrite().enabled();
        this.queryRewriteMaxTurns = props.queryRewrite().maxTurns();
        this.defaultTopK = props.retrieval().topK();
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ask(@RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        String traceId = TraceIds.newId();
        int topK = request.topK() == null ? defaultTopK : request.topK();

        return Mono.fromCallable(() -> prepare(request, topK, traceId))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(err -> {
                    // F4.2：检索/装配异常 → 友好拒答流，不把堆栈打成 500
                    log.error("Ask prepare failed: traceId={}, err={}", traceId, err.toString());
                    AskResult refused = AskResult.refused(
                            "PIPELINE_ERROR",
                            "服务暂时不可用：" + summarize(err),
                            traceId
                    );
                    return Mono.just(PreparedAsk.refused(refused, request.sessionId()));
                })
                .flatMapMany(prepared -> emit(prepared, traceId));
    }

    private PreparedAsk prepare(AskRequest request, int topK, String traceId) {
        String originalQuestion = request.question();
        String sessionId = blankToNull(request.sessionId());
        // 生成侧保留历史拼接（追问需要上下文组织语气）；都在写入本轮用户消息之前取，避免历史重复当前问句
        String promptQuestion = originalQuestion;
        if (sessionId != null) {
            promptQuestion = chatSessionService.buildContextualQuestion(
                    sessionId, originalQuestion, 5);
        }

        List<String> kbIds = knowledgeFilterResolver.resolveAllowedKbIds(request.resolvedKnowledgeBaseId());
        String filterExpr = knowledgeFilterResolver.toFilterExpression(kbIds);
        List<String> sqlKbIds = kbIds.isEmpty() ? null : kbIds;

        traceRecorder.start(traceId, "ask");
        long t0 = System.currentTimeMillis();
        // 检索侧：多轮时用 LLM 把指代/省略改写成独立问句（拼接历史会稀释向量语义）；
        // 改写超时/异常回退原文，单轮无历史不发 LLM 调用（见 core QueryRewriter）
        String searchQuery = originalQuestion;
        if (sessionId != null) {
            if (queryRewriteEnabled) {
                String historyText = chatSessionService.recentHistoryText(sessionId, queryRewriteMaxTurns);
                QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(historyText, originalQuestion);
                searchQuery = rewrite.query();
                Map<String, Object> rewriteMeta = new LinkedHashMap<>();
                rewriteMeta.put("rewritten", rewrite.rewritten());
                rewriteMeta.put("fallbackReason", rewrite.fallbackReason() == null ? "" : rewrite.fallbackReason());
                traceRecorder.record(traceId, new TraceEvent(
                        "query_rewrite",
                        System.currentTimeMillis(),
                        rewrite.durationMs(),
                        "queryRewriter",
                        TraceRecorder.summarize(originalQuestion),
                        TraceRecorder.summarize(searchQuery),
                        null,
                        null,
                        rewriteMeta
                ));
            }
            chatSessionService.appendMessage(
                    sessionId, "user", originalQuestion, null, traceId, null, null);
        }
        List<RetrievedChunk> hits = retriever.retrieve(
                searchQuery, topK, null, filterExpr, sqlKbIds);
        Map<String, Object> retrievalMeta = new LinkedHashMap<>();
        retrievalMeta.put("hitCount", hits.size());
        retrievalMeta.put("knowledgeBaseIds", kbIds);
        retrievalMeta.put("sessionId", sessionId);
        traceRecorder.record(traceId, new TraceEvent(
                "retrieval",
                System.currentTimeMillis(),
                System.currentTimeMillis() - t0,
                "retriever",
                TraceRecorder.summarize(searchQuery),
                TraceRecorder.summarize("hits=" + hits.size()),
                null,
                null,
                retrievalMeta
        ));

        if (refusalPolicy.checkBeforeGeneration(hits, traceId).isPresent()) {
            log.info("Refused before generation: traceId={}, reason=NO_EVIDENCE, "
                    + "generator NOT invoked", traceId);
            AskResult refused = AskResult.refused(
                    RefusalPolicy.NO_EVIDENCE, "根据现有资料无法回答", traceId);
            persistAssistantIfNeeded(sessionId, refused.answer(), null, traceId, null, null);
            return PreparedAsk.refused(refused, sessionId);
        }

        String promptVersion = promptBuilder.version();
        String prompt = promptBuilder.build(promptQuestion, hits).orElseThrow();
        List<Citation> citations = citationAssembler.assemble(hits);
        log.info("Ask: traceId={}, hits={}, promptVersion={}, sessionId={}",
                traceId, hits.size(), promptVersion, sessionId);
        traceRecorder.record(traceId, new TraceEvent(
                "prompt",
                System.currentTimeMillis(),
                0,
                "prompt",
                promptVersion,
                "",
                null,
                null,
                Map.of("promptVersion", promptVersion)
        ));
        return PreparedAsk.ready(prompt, citations, promptVersion, sessionId);
    }

    private Flux<ServerSentEvent<String>> emit(PreparedAsk prepared, String traceId) {
        if (prepared.refusedResult() != null) {
            AskResult refused = prepared.refusedResult();
            return Flux.just(
                    sse("refused", refusedPayload(refused), traceId),
                    sse("done", donePayload(refused, 0.0, prepared.promptVersion()), traceId)
            );
        }

        Flux<ServerSentEvent<String>> citationEvents = Flux.fromIterable(prepared.citations())
                .map(c -> sse("citation", json(c), traceId));

        StringBuilder answerBuffer = new StringBuilder();
        AtomicBoolean degraded = new AtomicBoolean(false);
        AtomicReference<AskResult> degradedResult = new AtomicReference<>();
        // 用量随本次订阅走（Completed 事件携带），不再读生成器实例状态——并发请求计量不串号
        AtomicReference<Generator.AskTokenUsage> streamUsage =
                new AtomicReference<>(Generator.AskTokenUsage.ZERO);

        Flux<ServerSentEvent<String>> tokenEvents = generator.stream(prepared.prompt())
                .mapNotNull(event -> switch (event) {
                    case GenerationEvent.Token t -> {
                        answerBuffer.append(t.text());
                        yield sse("token", json(Map.of("text", t.text())), traceId);
                    }
                    case GenerationEvent.Completed c -> {
                        streamUsage.set(c.usage());
                        // 结束事件不直接出 SSE，统一并进 done 事件
                        yield null;
                    }
                })
                .onErrorResume(err -> {
                    degraded.set(true);
                    String reason = err instanceof GenerationDegradedException g
                            ? g.reasonCode()
                            : GenerationDegradedException.REASON_ERROR;
                    String message = err instanceof GenerationDegradedException g
                            ? g.getMessage()
                            : "生成失败：" + summarize(err);
                    log.error("Generation degraded: traceId={}, reason={}, err={}",
                            traceId, reason, err.toString());
                    AskResult failed = AskResult.refused(reason, message, traceId);
                    degradedResult.set(failed);
                    return Flux.just(sse("refused", refusedPayload(failed), traceId));
                });

        Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
            if (degraded.get()) {
                AskResult failed = degradedResult.get();
                persistAssistantIfNeeded(prepared.sessionId(), failed.answer(), null, traceId,
                        prepared.promptVersion(), null);
                return sse("done", donePayload(failed, 0.0, prepared.promptVersion()), traceId);
            }
            String answer = answerBuffer.toString();
            if (refusalPolicy.checkAfterGeneration(answer, traceId).isPresent()) {
                log.warn("Empty answer from generator: traceId={}", traceId);
                AskResult refused = AskResult.refused(
                        RefusalPolicy.EMPTY_ANSWER, "模型未给出有效回答，请重试或换个问法", traceId);
                persistAssistantIfNeeded(prepared.sessionId(), refused.answer(), null, traceId,
                        prepared.promptVersion(), null);
                return sse("refused", refusedPayload(refused), traceId);
            }
            citationAssembler.validate(answer, prepared.citations());
            Generator.AskTokenUsage usage = streamUsage.get();
            tokenMeter.add(traceId, usage.prompt(), usage.completion());
            TokenMeter.Usage metered = tokenMeter.usage(traceId);
            double cost = tokenMeter.estimateCostUsd(traceId);
            String promptVersion = prepared.promptVersion() == null
                    ? promptBuilder.version()
                    : prepared.promptVersion();
            traceRecorder.record(traceId, new TraceEvent(
                    "generation",
                    System.currentTimeMillis(),
                    0,
                    "generator",
                    promptVersion,
                    TraceRecorder.summarize(answer),
                    metered.prompt(),
                    metered.completion(),
                    Map.of(
                            "estimatedCostUsd", cost,
                            "promptVersion", promptVersion
                    )
            ));
            AskResult result = AskResult.answered(answer, prepared.citations(), traceId,
                    new AskResult.TokenUsage(metered.prompt(), metered.completion()));
            String citationsJson = json(prepared.citations());
            persistAssistantIfNeeded(prepared.sessionId(), answer, citationsJson, traceId,
                    promptVersion, metered.prompt() + metered.completion());
            return sse("done", donePayload(result, cost, promptVersion), traceId);
        }).subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(citationEvents, tokenEvents, doneEvent);
    }

    private void persistAssistantIfNeeded(
            String sessionId,
            String content,
            String citationsJson,
            String traceId,
            String promptVersion,
            Integer tokenCount
    ) {
        if (sessionId == null || content == null) {
            return;
        }
        try {
            chatSessionService.appendMessage(
                    sessionId, "assistant", content, citationsJson, traceId, promptVersion, tokenCount);
        } catch (Exception e) {
            log.warn("Persist chat message failed: sessionId={}, err={}", sessionId, e.toString());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private record PreparedAsk(
            AskResult refusedResult,
            String prompt,
            List<Citation> citations,
            String promptVersion,
            String sessionId
    ) {
        static PreparedAsk refused(AskResult refused, String sessionId) {
            return new PreparedAsk(refused, null, List.of(), null, sessionId);
        }

        static PreparedAsk ready(
                String prompt,
                List<Citation> citations,
                String promptVersion,
                String sessionId
        ) {
            return new PreparedAsk(null, prompt, citations, promptVersion, sessionId);
        }
    }

    private String donePayload(AskResult result, double estimatedCostUsd, String promptVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refused", result.refused());
        payload.put("traceId", result.traceId());
        payload.put("promptTokens", result.tokenUsage().prompt());
        payload.put("completionTokens", result.tokenUsage().completion());
        payload.put("estimatedCostUsd", estimatedCostUsd);
        // F4.1：每次生成记录 prompt 版本，便于评测/回滚对齐
        if (promptVersion != null) {
            payload.put("promptVersion", promptVersion);
        }
        return json(payload);
    }

    private String refusedPayload(AskResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", result.refuseReason());
        payload.put("message", result.answer());
        return json(payload);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize SSE payload", e);
        }
    }

    private static String summarize(Throwable err) {
        return err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
    }

    private static ServerSentEvent<String> sse(String event, String data, String traceId) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .id(traceId)
                .build();
    }
}
