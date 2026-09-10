package com.ragpilot.core.domain;

import java.util.Map;

/**
 * 文本分块：分块器的产出、向量化的输入。
 *
 * <p>链路位置：解析 → <b>分块</b>（本对象的产出）→ 向量化 → 入库；
 * 检索命中后又会随 {@link RetrievedChunk} 回到生成链路。
 *
 * @param id       分块 ID，约定格式 {@code docId#序号}（如 spring-bean-lifecycle#3），全局唯一
 * @param docId    所属文档 ID，与 {@link SourceDocument#id()} 对应
 * @param content  分块文本内容
 * @param index    块在文档内的顺序下标，从 0 开始；用于排序与拼接还原
 * @param metadata 从源文档继承的元数据（title 等），随块入库，citation 展示用
 */
public record TextChunk(
        String id,
        String docId,
        String content,
        int index,
        Map<String, String> metadata
) {

    /** 紧凑构造器：metadata 做防御性拷贝，保证对象不可变。 */
    public TextChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
