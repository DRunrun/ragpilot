package com.ragpilot.core.domain;

import java.util.Map;

/**
 * 原始文档：ingestion 链路的起点对象。
 *
 * <p>链路位置：<b>解析</b>（本对象的产出）→ 分块 → 向量化 → 入库。
 * 由 {@code DocumentParser} 读取 .md/.txt 文件后构造。
 *
 * <p>为什么 metadata 用 {@code Map<String,String>} 而不是再拆字段：
 * 不同来源文档的元信息千差万别（标题、作者、抓取时间……），
 * 元数据本来就是开放集合；但注意只放 String，不放对象，保持契约可序列化。
 *
 * @param id       文档唯一 ID，约定用文件名（不含扩展名），如 spring-bean-lifecycle
 * @param uri      文档来源位置（文件路径或 URL），citation 展示与溯源用
 * @param mime     内容类型，如 text/markdown、text/plain
 * @param rawText  解析后的纯文本（已剥离格式标记）
 * @param metadata 元数据，至少应含 title（Markdown 一级标题），供 citation 展示
 */
public record SourceDocument(
        String id,
        String uri,
        String mime,
        String rawText,
        Map<String, String> metadata
) {

    /** 紧凑构造器：metadata 做防御性拷贝，保证对象不可变。 */
    public SourceDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
