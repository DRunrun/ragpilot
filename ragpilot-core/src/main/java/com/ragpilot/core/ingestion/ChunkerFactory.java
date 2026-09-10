package com.ragpilot.core.ingestion;

import java.util.List;

/**
 * 按策略构造 {@link Chunker}：供预览 API 与运行时装配共用，避免两处拼装逻辑漂移。
 *
 * <p>链路位置：ingestion 分块步骤的工厂（无 Spring）。
 */
public final class ChunkerFactory {

    private ChunkerFactory() {
    }

    /**
     * @param strategy   策略；null 视为 FIXED
     * @param size       块大小
     * @param overlap    重叠
     * @param separators 仅 RECURSIVE 使用；可为 null
     */
    public static Chunker create(
            ChunkStrategy strategy,
            int size,
            int overlap,
            List<String> separators
    ) {
        ChunkStrategy s = strategy == null ? ChunkStrategy.FIXED : strategy;
        return switch (s) {
            case FIXED -> new FixedSizeChunker(size, overlap);
            case HEADING -> new HeadingChunker(size, overlap);
            case RECURSIVE -> new RecursiveChunker(size, overlap, separators);
        };
    }
}
