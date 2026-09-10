package com.ragpilot.core.domain;

/**
 * 检索命中：一次检索返回的单条结果，= 原始分块 + 打分信息。
 *
 * <p>链路位置：<b>检索</b>（本对象的产出）→ minScore 过滤 → Prompt 组装 → 生成。
 * 命中列表为空或全部低分时，上层据此走拒答（见 RefusalPolicy）。
 *
 * @param chunk   命中的分块本体
 * @param score   相似度得分，口径随 channel 不同：
 *                VECTOR 为余弦相似度（越大越相关）；M2 接入 BM25/RRF 时各自定义
 * @param rank    该路召回内的名次，从 1 开始；融合排序（RRF）需要用到
 * @param channel 命中来源通道；M1 只有 VECTOR，预留枚举避免 M2 改结构
 */
public record RetrievedChunk(
        TextChunk chunk,
        double score,
        int rank,
        Channel channel
) {

    /**
     * 召回通道：标记这条命中是哪一路检索产出的。
     *
     * <p>M1 只用 VECTOR；BM25 与 RRF 是 M2 的事，枚举值先占位，
     * 这样 M2 接入混合检索时领域模型不用动。
     */
    public enum Channel {
        /** 向量语义召回（M1 唯一通道）。 */
        VECTOR,
        /** 关键词/BM25 召回（M2）。 */
        BM25,
        /** RRF 融合后的结果（M2）。 */
        RRF
    }
}
