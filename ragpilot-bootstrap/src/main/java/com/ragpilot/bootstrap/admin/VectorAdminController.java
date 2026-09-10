package com.ragpilot.bootstrap.admin;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库运维 API（ADM-3.8～3.10）：统计、块排查、危险清空。
 *
 * <p>链路位置：Admin 向量库页；不提供主上传入口（上传走知识库）。
 */
@RestController
@RequestMapping("/api/admin/v1/vector")
public class VectorAdminController {

    private final VectorChunkStore vectorChunkStore;

    public VectorAdminController(VectorChunkStore vectorChunkStore) {
        this.vectorChunkStore = vectorChunkStore;
    }

    @GetMapping("/stats")
    public Mono<AdminApiResponse<Map<String, Object>>> stats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalChunks", vectorChunkStore.countAll());
            data.put("byKnowledgeBase", vectorChunkStore.countByKb());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/by-doc")
    public Mono<AdminApiResponse<Map<String, Object>>> byDoc(
            @RequestParam String knowledgeBaseId
    ) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> items = vectorChunkStore.countByDoc(knowledgeBaseId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("knowledgeBaseId", knowledgeBaseId);
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/inspect")
    public Mono<AdminApiResponse<Map<String, Object>>> inspect(
            @RequestParam String docId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> items = vectorChunkStore.inspectByDocId(docId, limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("docId", docId);
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/all")
    public Mono<AdminApiResponse<Map<String, Object>>> clearAll(
            @RequestParam(required = false) String confirm
    ) {
        return Mono.fromCallable(() -> {
            if (!"DELETE_ALL".equals(confirm)) {
                return AdminApiResponse.<Map<String, Object>>fail(
                        "BAD_REQUEST", "清空须传 confirm=DELETE_ALL");
            }
            int n = vectorChunkStore.deleteAll();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deleted", n);
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
