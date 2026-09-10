package com.ragpilot.bootstrap.admin;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 Admin API（ADM-3.1～3.4）。
 *
 * <p>链路位置：HTTP → KnowledgeBase/DocumentService → ingestion / vector_store。
 */
@RestController
@RequestMapping("/api/admin/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentService documentService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeDocumentService documentService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
    }

    public record CreateKbRequest(String name, String description) {
    }

    public record PatchKbRequest(String name, String description, Boolean enabled) {
    }

    @GetMapping
    public Mono<AdminApiResponse<Map<String, Object>>> list() {
        return Mono.fromCallable(() -> {
            List<KnowledgeBaseService.KnowledgeBaseView> items = knowledgeBaseService.list();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<AdminApiResponse<KnowledgeBaseService.KnowledgeBaseView>> create(
            @RequestBody CreateKbRequest body
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(
                        knowledgeBaseService.create(body == null ? null : body.name(),
                                body == null ? null : body.description()));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<KnowledgeBaseService.KnowledgeBaseView>fail(
                        "BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{kbId}")
    public Mono<AdminApiResponse<KnowledgeBaseService.KnowledgeBaseView>> get(
            @PathVariable String kbId
    ) {
        return Mono.fromCallable(() -> knowledgeBaseService.get(kbId)
                        .map(AdminApiResponse::success)
                        .orElseGet(() -> AdminApiResponse.fail("NOT_FOUND", "知识库不存在: " + kbId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{kbId}")
    public Mono<AdminApiResponse<KnowledgeBaseService.KnowledgeBaseView>> patch(
            @PathVariable String kbId,
            @RequestBody PatchKbRequest body
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(knowledgeBaseService.patch(
                        kbId,
                        body == null ? null : body.name(),
                        body == null ? null : body.description(),
                        body == null ? null : body.enabled()));
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<KnowledgeBaseService.KnowledgeBaseView>fail(
                        "NOT_FOUND", e.getMessage());
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<KnowledgeBaseService.KnowledgeBaseView>fail(
                        "BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{kbId}")
    public Mono<AdminApiResponse<Map<String, Object>>> delete(
            @PathVariable String kbId,
            @RequestParam(required = false) String confirm
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(knowledgeBaseService.delete(kbId, confirm));
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{kbId}/documents")
    public Mono<AdminApiResponse<Map<String, Object>>> listDocs(@PathVariable String kbId) {
        return Mono.fromCallable(() -> {
            try {
                List<KnowledgeDocumentService.DocumentView> items = documentService.list(kbId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", items);
                data.put("total", items.size());
                return AdminApiResponse.success(data);
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/{kbId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<AdminApiResponse<KnowledgeDocumentService.DocumentView>> upload(
            @PathVariable String kbId,
            @RequestPart("file") FilePart file
    ) {
        String filename = file.filename();
        return DataBufferUtils.join(file.content())
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return bytes;
                })
                .flatMap(bytes -> Mono.fromCallable(() -> {
                    try {
                        return AdminApiResponse.success(documentService.upload(kbId, filename, bytes));
                    } catch (KnowledgeBaseService.NotFoundException e) {
                        return AdminApiResponse.<KnowledgeDocumentService.DocumentView>fail(
                                "NOT_FOUND", e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return AdminApiResponse.<KnowledgeDocumentService.DocumentView>fail(
                                "BAD_REQUEST", e.getMessage());
                    } catch (Exception e) {
                        return AdminApiResponse.<KnowledgeDocumentService.DocumentView>fail(
                                "INGEST_FAILED", e.getMessage());
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/{kbId}/documents/samples")
    public Mono<AdminApiResponse<Map<String, Object>>> samples(@PathVariable String kbId) {
        return Mono.fromCallable(() -> {
            try {
                List<KnowledgeDocumentService.DocumentView> items = documentService.ingestSamples(kbId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", items);
                data.put("total", items.size());
                return AdminApiResponse.success(data);
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{kbId}/documents/{docId}/reingest")
    public Mono<AdminApiResponse<KnowledgeDocumentService.DocumentView>> reingest(
            @PathVariable String kbId,
            @PathVariable String docId
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(documentService.reingest(kbId, docId));
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<KnowledgeDocumentService.DocumentView>fail(
                        "NOT_FOUND", e.getMessage());
            } catch (Exception e) {
                return AdminApiResponse.<KnowledgeDocumentService.DocumentView>fail(
                        "INGEST_FAILED", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{kbId}/documents/{docId}")
    public Mono<AdminApiResponse<Map<String, Object>>> deleteDoc(
            @PathVariable String kbId,
            @PathVariable String docId
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(documentService.delete(kbId, docId));
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{kbId}/documents/{docId}/chunks")
    public Mono<AdminApiResponse<Map<String, Object>>> chunks(
            @PathVariable String kbId,
            @PathVariable String docId
    ) {
        return Mono.fromCallable(() -> {
            try {
                List<Map<String, Object>> items = documentService.listChunks(kbId, docId);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", items);
                data.put("total", items.size());
                return AdminApiResponse.success(data);
            } catch (KnowledgeBaseService.NotFoundException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
