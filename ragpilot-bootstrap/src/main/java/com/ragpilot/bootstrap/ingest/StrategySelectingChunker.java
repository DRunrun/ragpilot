package com.ragpilot.bootstrap.ingest;

import com.ragpilot.bootstrap.admin.RuntimeConfigService;
import com.ragpilot.core.ingestion.ChunkStrategy;
import com.ragpilot.core.ingestion.Chunker;
import com.ragpilot.core.ingestion.ChunkerFactory;

import java.util.List;

/**
 * 按 DB 覆盖层实时选择分块策略的委托分块器（ADM-2.3）。
 *
 * <p>链路位置：ingestion 分块步骤的 bootstrap 适配——每次 {@link #chunk} 读生效配置，
 * 保证 Admin 改策略后「后续摄入」立刻走新切法，无需重启。
 */
public final class StrategySelectingChunker implements Chunker {

    private final RuntimeConfigService runtimeConfig;

    public StrategySelectingChunker(RuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public List<String> chunk(String text) {
        return resolve().chunk(text);
    }

    /** 按当前生效配置构造具体 Chunker（预览 API 也可复用同一解析逻辑）。 */
    public Chunker resolve() {
        ChunkStrategy strategy = ChunkStrategy.parse(runtimeConfig.chunkStrategy());
        return ChunkerFactory.create(
                strategy,
                runtimeConfig.chunkSize(),
                runtimeConfig.chunkOverlap(),
                runtimeConfig.chunkSeparators()
        );
    }

    public Chunker resolve(
            ChunkStrategy strategyOverride,
            Integer sizeOverride,
            Integer overlapOverride,
            List<String> separatorsOverride
    ) {
        ChunkStrategy strategy = strategyOverride != null
                ? strategyOverride
                : ChunkStrategy.parse(runtimeConfig.chunkStrategy());
        int size = sizeOverride != null ? sizeOverride : runtimeConfig.chunkSize();
        int overlap = overlapOverride != null ? overlapOverride : runtimeConfig.chunkOverlap();
        List<String> seps = separatorsOverride != null
                ? separatorsOverride
                : runtimeConfig.chunkSeparators();
        return ChunkerFactory.create(strategy, size, overlap, seps);
    }
}
