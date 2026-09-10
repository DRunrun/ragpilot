package com.ragpilot.bootstrap.admin;

import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.RetrievalMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试 Admin API（ADM-8）：三模式对比，可带 knowledgeBaseId。
 */
@RestController
@RequestMapping("/api/admin/v1/retrieve")
public class RetrieveAdminController {

    private final ModeAwareRetriever modeAwareRetriever;
    private final KnowledgeFilterResolver knowledgeFilterResolver;
    private final RuntimeConfigService runtimeConfigService;

    public RetrieveAdminController(
            ModeAwareRetriever modeAwareRetriever,
            KnowledgeFilterResolver knowledgeFilterResolver,
            RuntimeConfigService runtimeConfigService
    ) {
        this.modeAwareRetriever = modeAwareRetriever;
        this.knowledgeFilterResolver = knowledgeFilterResolver;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/modes")
    public Mono<AdminApiResponse<Map<String, Object>>> modes(
            @RequestParam String q,
            @RequestParam(required = false) Integer topK,
            @RequestParam(required = false) String knowledgeBaseId
    ) {
        return Mono.fromCallable(() -> {
            if (q == null || q.isBlank()) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", "q 不能为空");
            }
            int k = topK == null ? runtimeConfigService.topK() : topK;
            List<String> kbIds = knowledgeFilterResolver.resolveAllowedKbIds(knowledgeBaseId);
            String filterExpr = knowledgeFilterResolver.toFilterExpression(kbIds);
            List<String> sqlKbIds = kbIds.isEmpty() ? null : kbIds;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", q.strip());
            data.put("topK", k);
            data.put("knowledgeBaseIds", kbIds);
            data.put("filterExpression", filterExpr);
            data.put("defaultMode", modeAwareRetriever.defaultMode().name());

            List<Map<String, Object>> modes = new ArrayList<>();
            for (RetrievalMode mode : RetrievalMode.values()) {
                List<RetrievedChunk> hits = modeAwareRetriever.retrieve(
                        q.strip(), k, mode, filterExpr, sqlKbIds);
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("mode", mode.name());
                one.put("hitCount", hits.size());
                one.put("hits", hits.stream().map(this::toHit).toList());
                modes.add(one);
            }
            data.put("modes", modes);
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> toHit(RetrievedChunk hit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", hit.rank());
        m.put("score", hit.score());
        m.put("channel", hit.channel().name());
        m.put("docId", hit.chunk().docId());
        m.put("chunkId", hit.chunk().id());
        String content = hit.chunk().content();
        m.put("snippet", content == null ? ""
                : content.substring(0, Math.min(content.length(), 240)));
        m.put("knowledgeBaseId", hit.chunk().metadata().get("knowledgeBaseId"));
        return m;
    }
}
