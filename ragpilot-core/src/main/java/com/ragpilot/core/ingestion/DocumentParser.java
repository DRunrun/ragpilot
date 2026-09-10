package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.SourceDocument;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档解析端口：把磁盘上的原始文件读成领域对象 {@link SourceDocument}。
 *
 * <p>链路位置：ingestion 的第一步（<b>解析</b> → 分块 → 向量化 → 入库）。
 *
 * <p>为什么抽接口：M1 只吃 Markdown/纯文本，后续接 HTML、PDF（Tika）
 * 都是新增实现，管线代码不动。
 */
public interface DocumentParser {

    /**
     * 判断本解析器是否能处理该文件（按扩展名）。
     *
     * @param path 文件路径，只用于判断扩展名，不要求文件存在
     * @return 能处理返回 true
     */
    boolean supports(Path path);

    /**
     * 解析文件为源文档。
     *
     * <p>职责边界：只做「格式剥离 + 元数据提取」，不做分块与清洗停用词。
     *
     * @param path 待解析文件，必须存在且可读
     * @return 源文档；rawText 为剥离格式标记后的纯文本，metadata 至少含 title
     * @throws IOException              读取失败
     * @throws IllegalArgumentException 文件不存在或扩展名不支持
     */
    SourceDocument parse(Path path) throws IOException;
}
