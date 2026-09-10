package com.ragpilot.bootstrap.admin;

import com.ragpilot.bootstrap.ingest.StrategySelectingChunker;
import com.ragpilot.core.ingestion.ChunkStrategy;
import com.ragpilot.core.ingestion.Chunker;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块试切预览 API（ADM-2.4）：纯计算、不写向量库。
 *
 * <p>链路位置：Admin → 本接口 → {@link StrategySelectingChunker} → core Chunker。
 */
@RestController
@RequestMapping("/api/admin/v1/chunk")
public class ChunkPreviewController {

    private final StrategySelectingChunker selectingChunker;
    private final RuntimeConfigService runtimeConfigService;

    public ChunkPreviewController(
            StrategySelectingChunker selectingChunker,
            RuntimeConfigService runtimeConfigService
    ) {
        this.selectingChunker = selectingChunker;
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * 试切请求：可临时覆盖策略/参数以便对比；不传则用生效配置。
     */
    public record PreviewRequest(
            String text,
            String strategy,
            Integer size,
            Integer overlap,
            List<String> separators
    ) {
    }

    public record ChunkPreviewItem(int index, int length, String content) {
    }

    @PostMapping("/preview")
    public AdminApiResponse<Map<String, Object>> preview(@RequestBody PreviewRequest request) {
        if (request == null || request.text() == null) {
            return AdminApiResponse.fail("BAD_REQUEST", "text 不能为空");
        }
        ChunkStrategy strategy = request.strategy() == null || request.strategy().isBlank()
                ? ChunkStrategy.parse(runtimeConfigService.chunkStrategy())
                : ChunkStrategy.parse(request.strategy());
        int size = request.size() != null ? request.size() : runtimeConfigService.chunkSize();
        int overlap = request.overlap() != null ? request.overlap() : runtimeConfigService.chunkOverlap();
        List<String> separators = request.separators() != null
                ? request.separators()
                : runtimeConfigService.chunkSeparators();

        if (size <= 0 || overlap < 0 || overlap >= size) {
            return AdminApiResponse.fail("BAD_REQUEST", "size/overlap 非法：须 size>0 且 0≤overlap<size");
        }

        Chunker chunker = selectingChunker.resolve(strategy, size, overlap, separators);
        List<String> parts = chunker.chunk(request.text());
        List<ChunkPreviewItem> items = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String c = parts.get(i);
            items.add(new ChunkPreviewItem(i, c.length(), c));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategy", strategy.name());
        data.put("size", size);
        data.put("overlap", overlap);
        data.put("separators", separators);
        data.put("chunkCount", items.size());
        data.put("items", items);
        data.put("hint", "策略变更仅影响后续摄入；已入库文档请在知识库中重灌。");
        return AdminApiResponse.success(data);
    }
}
