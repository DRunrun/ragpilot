package com.ragpilot.core.domain;

/**
 * 引用条目：答案中 {@code [n]} 标记对应的来源凭证，是 RAG「可溯源」的最小单元。
 *
 * <p>链路位置：检索命中 → 生成 → <b>Citation 组装</b>（本对象的产出）→ 随 AskResult 返回前端。
 *
 * <p>为什么 snippet 必须带：前端只展示 [1] 编号没有说服力，
 * 点开能看到原文片段，面试官/用户才能验证「答案确实来自这段」。
 *
 * @param index   引用序号，从 1 开始，与答案文本中的 {@code [n]} 严格对应
 * @param docId   来源文档 ID
 * @param chunkId 来源分块 ID，格式约定 {@code docId#序号}
 * @param snippet 原文片段（截断到可读长度），供前端展开查看
 */
public record Citation(int index, String docId, String chunkId, String snippet) {
}
