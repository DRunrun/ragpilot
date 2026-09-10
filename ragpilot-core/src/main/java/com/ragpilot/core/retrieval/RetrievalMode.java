package com.ragpilot.core.retrieval;

/**
 * 检索模式：消融实验与配置开关共用的枚举。
 *
 * <p>链路位置：检索编排入口；决定走单路向量、混合召回，还是混合后再 Rerank。
 * <p>为什么是枚举而不是布尔组合：三种 setup 是互斥的实验臂，避免
 * {@code hybrid=true + rerank=false} 这类组合歧义；评测 Runner 按 mode 一键切换。
 */
public enum RetrievalMode {

    /** 仅向量语义召回（M1 默认，对照组）。 */
    VECTOR,

    /** 向量 + BM25，经 RRF 融合（实验 A 的混合臂）。 */
    HYBRID,

    /** 混合召回后再过 Reranker（实验 B 的有 Rerank 臂）。 */
    HYBRID_RERANK
}
