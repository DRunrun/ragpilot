package com.ragpilot.bootstrap.web;

import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.bootstrap.retrieval.PgFullTextRetriever;
import com.ragpilot.bootstrap.retrieval.VectorRetriever;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.core.retrieval.Retriever;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试接口：单路验收 + 三模式对比，不走生成。
 *
 * <p>链路位置：F2.1～F2.4 验收入口。正式问答走 {@code /api/v1/ask}（默认 VECTOR）。
 */
@RestController
@RequestMapping("/api/v1/retrieve")
public class RetrieveDebugController {

    private final VectorRetriever vectorRetriever;
    private final PgFullTextRetriever keywordRetriever;
    private final ModeAwareRetriever modeAwareRetriever;
    private final int defaultTopK;

    public RetrieveDebugController(
            VectorRetriever vectorRetriever,
            PgFullTextRetriever keywordRetriever,
            ModeAwareRetriever modeAwareRetriever,
            com.ragpilot.bootstrap.config.RagPilotProperties props
    ) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.modeAwareRetriever = modeAwareRetriever;
        this.defaultTopK = props.retrieval().topK();
    }

    /**
     * 关键词 / BM25 近似召回验收。
     *
     * <p>示例：{@code GET /api/v1/retrieve/keyword?q=BeanPostProcessor}
     */
    @GetMapping("/keyword")
    public Mono<Map<String, Object>> keyword(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "topK", required = false) Integer topK
    ) {
        return retrieve("keyword", keywordRetriever, q, topK);
    }

    /**
     * 向量召回对照。
     *
     * <p>示例：{@code GET /api/v1/retrieve/vector?q=BeanPostProcessor}
     */
    @GetMapping("/vector")
    public Mono<Map<String, Object>> vector(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "topK", required = false) Integer topK
    ) {
        return retrieve("vector", vectorRetriever, q, topK);
    }

    /**
     * F2.4 验收：同一问题跑 VECTOR / HYBRID / HYBRID_RERANK，产出三份可对比命中列表。
     *
     * <p>示例：{@code GET /api/v1/retrieve/modes?q=BeanPostProcessor}
     */
    @GetMapping("/modes")
    public Mono<Map<String, Object>> modes(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "topK", required = false) Integer topK
    ) {
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        int k = topK == null ? defaultTopK : topK;
        String query = q.strip();
        return Mono.fromCallable(() -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("query", query);
                    body.put("topK", k);
                    body.put("defaultMode", modeAwareRetriever.defaultMode().name());
                    List<Map<String, Object>> modes = new ArrayList<>();
                    for (RetrievalMode mode : RetrievalMode.values()) {
                        List<RetrievedChunk> hits = modeAwareRetriever.retrieve(query, k, mode);
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("mode", mode.name());
                        one.put("hitCount", hits.size());
                        one.put("hits", hits.stream().map(this::toHitView).toList());
                        modes.add(one);
                    }
                    body.put("modes", modes);
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Map<String, Object>> retrieve(
            String mode,
            Retriever retriever,
            String q,
            Integer topK
    ) {
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is required");
        }
        int k = topK == null ? defaultTopK : topK;
        return Mono.fromCallable(() -> {
                    List<RetrievedChunk> hits = retriever.retrieve(q.strip(), k);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("mode", mode);
                    body.put("query", q.strip());
                    body.put("topK", k);
                    body.put("hitCount", hits.size());
                    body.put("hits", hits.stream().map(this::toHitView).toList());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 调试响应视图：只暴露验收需要的字段，避免把整段原文刷屏。 */
    private Map<String, Object> toHitView(RetrievedChunk hit) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rank", hit.rank());
        view.put("score", hit.score());
        view.put("channel", hit.channel().name());
        view.put("docId", hit.chunk().docId());
        view.put("chunkId", hit.chunk().id());
        String content = hit.chunk().content();
        view.put("snippet", content == null ? ""
                : content.substring(0, Math.min(content.length(), 200)));
        return view;
    }
}
