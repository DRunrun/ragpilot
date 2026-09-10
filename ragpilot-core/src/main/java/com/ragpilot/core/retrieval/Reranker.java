package com.ragpilot.core.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;

import java.util.List;

/**
 * 重排序器端口：对初召回候选按「查询-文档」相关性重新打分排序。
 *
 * <p>链路位置：多路召回 / RRF 融合之后、Prompt 组装之前。
 * <p>为什么抽接口：Rerank 对延迟与模型依赖重，消融实验需要「开/关」对比；
 * 关闭时用 {@link NoOpReranker} 保证行为与 M1 完全一致。
 */
public interface Reranker {

    /**
     * 对候选块重排序并截断到 topK。
     *
     * @param query      用户问题原文
     * @param candidates 初召回候选（可含 VECTOR / BM25 / RRF 通道结果）
     * @param topK       最终保留条数，必须 &gt; 0
     * @return 按重排分降序的命中；candidates 为空时返回空列表；
     *         实现不得返回 null
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
