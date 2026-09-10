package com.ragpilot.core.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;

import java.util.List;

/**
 * 检索器端口：把用户问题变成一组带分数的命中块。
 *
 * <p>链路位置：问答链路的第二环（<b>检索</b> → Prompt 组装 → 生成）。
 *
 * <p>为什么抽接口：M1 只有向量单路；M2 会加 BM25 路并做 RRF 融合，
 * 届时是「新增实现 + 编排」，本接口与上层问答逻辑不动。
 */
public interface Retriever {

    /**
     * 检索与问题最相关的分块。
     *
     * @param query 用户问题原文（实现方可自行做基础清洗）
     * @param topK  最多返回条数，必须 &gt; 0
     * @return 按分数降序的命中列表；无命中或全部低于阈值时返回空列表
     *         （上层 RefusalPolicy 据此拒答，检索器自己不做拒答决策）
     */
    List<RetrievedChunk> retrieve(String query, int topK);
}
