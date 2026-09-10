package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.TextChunk;

import java.util.List;

/**
 * 分块写入端口：把分块持久化到向量库（含向量化）。
 *
 * <p>链路位置：ingestion 的最后一步（解析 → 分块 → <b>写入/向量化</b>）。
 *
 * <p>为什么是接口（六边形架构的「端口」）：
 * core 不依赖任何具体向量库或 Spring AI，只认这个接口；
 * 具体实现（Spring AI PGVector）放在 bootstrap 的适配器里。
 * 将来换向量库只换适配器，core 与评测代码零改动。
 */
public interface ChunkWriter {

    /**
     * 批量写入分块。实现方负责向量化与入库。
     *
     * @param chunks 待写入分块列表，空列表直接返回不产生副作用
     * @throws RuntimeException 写入失败时抛出，由调用方决定重试或报错
     */
    void writeAll(List<TextChunk> chunks);
}
