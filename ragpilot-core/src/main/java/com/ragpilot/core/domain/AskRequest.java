package com.ragpilot.core.domain;

import java.util.Map;

/**
 * 问答请求入参：{@code POST /api/v1/ask} 的请求契约。
 *
 * <p>链路位置：HTTP 入口（AskController）→ <b>本对象</b> → 检索 → 生成。
 *
 * <p>为什么是独立顶层 record：它是对外 API 契约的一部分（与 {@link AskResult} 对等），
 * 独立出来便于在 spec 与代码之间一一对应，也避免 AskResult 同时承载「请求 + 响应」两种语义。
 *
 * <p>注意：filters 是过渡设计。稳定过滤维度（如 {@code knowledgeBaseId}）已提升为独立字段。
 *
 * @param question         用户原始问题
 * @param topK             本次检索返回块数，为 null 时用配置默认值
 * @param filters          其它元数据过滤（可选）；也兼容 {@code filters.knowledgeBaseId}
 * @param knowledgeBaseId  指定知识库；null 时用 Overlay 默认库或全部 enabled 库（ADM-3.5）
 * @param sessionId        可选会话 ID；有则服务端拼多轮历史并落库消息（ADM-5.2）
 */
public record AskRequest(
        String question,
        Integer topK,
        Map<String, String> filters,
        String knowledgeBaseId,
        String sessionId
) {
    /** 紧凑构造器：filters 归一为不可变 map，永不为 null。 */
    public AskRequest {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    /** 解析生效的知识库 ID：顶层字段优先，其次 filters。 */
    public String resolvedKnowledgeBaseId() {
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            return knowledgeBaseId.strip();
        }
        String fromFilter = filters.get("knowledgeBaseId");
        if (fromFilter != null && !fromFilter.isBlank()) {
            return fromFilter.strip();
        }
        return null;
    }
}
