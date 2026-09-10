package com.ragpilot.core.ingestion;

/**
 * 摄入结果回执：一次 ingest 的产出摘要。
 *
 * <p>链路位置：ingestion 管线出口 → HTTP 接口把它序列化返回给调用方。
 *
 * @param docId      被摄入的文档 ID
 * @param chunkCount 实际写入的分块数；0 表示文档无有效内容（被跳过，未产生空向量）
 */
public record IngestReport(String docId, int chunkCount) {
}
