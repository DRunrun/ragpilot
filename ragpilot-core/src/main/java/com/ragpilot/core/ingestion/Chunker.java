package com.ragpilot.core.ingestion;

import java.util.List;

/**
 * 分块器端口：把长文本切成可独立向量化的块。
 *
 * <p>链路位置：ingestion 的第二步（解析 → <b>分块</b> → 向量化 → 入库）。
 *
 * <p>为什么抽接口：分块策略是消融实验的核心变量之一
 * （M1 固定大小、M2 可能换结构化/语义分块），
 * 上层管线只依赖本接口，换策略不动管线代码。
 */
public interface Chunker {

    /**
     * 执行分块。
     *
     * @param text 待分块的纯文本，不允许为 null
     * @return 不可变的分块文本列表；空文本/纯空白返回空列表（上层据此跳过，不产生空向量）
     */
    List<String> chunk(String text);
}
