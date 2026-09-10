package com.ragpilot.core.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 固定大小分块器：把长文本按<b>字符数</b>切成带重叠的块，是 M1 的默认分块策略。
 *
 * <p>链路位置：ingestion 的第二步（解析 → <b>分块</b> → 向量化 → 入库）。
 *
 * <p>为什么需要分块：
 * <ul>
 *   <li>Embedding 模型有输入长度上限，整篇文档塞不进去</li>
 *   <li>检索粒度太粗会把大量无关内容带进上下文，稀释信噪比也烧 token</li>
 * </ul>
 *
 * <p>为什么要重叠（overlap）：
 * 硬切会把一句话或一个概念从中间截断，导致两个块都不完整、都召回不上。
 * 让相邻块共享一段尾巴，可以保住跨边界的语义。
 *
 * <p>已知局限（M2 再优化）：按字符切不等于按 token 切，中英文混排时块的实际 token 数会飘；
 * 也不感知 Markdown 标题、代码块等结构边界。改进方向见 {@code docs/execution-plan.md} 的 M2。
 *
 * <p>F1.6 起实现 {@link Chunker} 端口接口，size/overlap 由 bootstrap 从
 * {@code ragpilot.chunk.*} 配置构造注入，代码内不再出现硬编码参数。
 */
public final class FixedSizeChunker implements Chunker {

    /** 每块的目标字符数。 */
    private final int size;

    /** 相邻两块的重叠字符数，必须小于 size，否则窗口无法前进会死循环。 */
    private final int overlap;

    /**
     * @param size    每块字符数，必须 &gt; 0；经验值 500～800（中文信息密度高，可比英文取小）
     * @param overlap 重叠字符数，取值 {@code [0, size)}；经验值为 size 的 10%～20%
     * @throws IllegalArgumentException 参数非法时抛出，宁可启动即失败也不要运行期切出诡异结果
     */
    public FixedSizeChunker(int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        // overlap >= size 会导致下面的 start = end - overlap 不前进甚至倒退，必须拦死
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap must be in [0, size)");
        }
        this.size = size;
        this.overlap = overlap;
    }

    /**
     * 执行分块。
     *
     * @param text 待分块的纯文本，不允许为 null
     * @return 不可变的分块列表；文本为空或纯空白时返回空列表（上层据此跳过该文档，不产生空向量）
     */
    @Override
    public List<String> chunk(String text) {
        Objects.requireNonNull(text, "text");
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            // 末尾不足一整块时按实际长度收口，不补齐也不丢弃
            int end = Math.min(start + size, normalized.length());
            parts.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            // 回退 overlap 个字符作为下一块起点，保住跨边界的语义
            start = end - overlap;
        }
        return List.copyOf(parts);
    }
}
