package com.ragpilot.core.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 标题（H1～H3）分块：一节（标题 + 正文）尽量一块。
 *
 * <p>链路位置：ingestion 第二步的可选策略（Admin {@code HEADING}）。
 *
 * <p>为什么需要：面试题/文档语料常以 {@code ### 题目标题} 组织；固定窗口会把多题粘在一块，
 * 或把题干切断，导致检索命中「沾边」块。标题切块让「一题一块」可测。
 *
 * <p>过长 section（超过 size）再用 {@link FixedSizeChunker} 二次切，避免单块撑爆上下文。
 */
public final class HeadingChunker implements Chunker {

    /** 行首 1～3 个 # 后跟空白，视为 H1～H3 标题行。 */
    private static final Pattern HEADING_LINE = Pattern.compile("(?m)^(#{1,3})\\s+.+$");

    private final int size;
    private final int overlap;
    private final FixedSizeChunker overflowSplitter;

    /**
     * @param size    单块上限字符数；section 超长时二次切分用
     * @param overlap 二次切分重叠；须满足 {@link FixedSizeChunker} 约束
     */
    public HeadingChunker(int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap must be in [0, size)");
        }
        this.size = size;
        this.overlap = overlap;
        this.overflowSplitter = new FixedSizeChunker(size, overlap);
    }

    /**
     * @param text 待分块文本；null 禁止；纯空白返回空列表
     */
    @Override
    public List<String> chunk(String text) {
        Objects.requireNonNull(text, "text");
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> sections = splitByHeadings(normalized);
        List<String> parts = new ArrayList<>();
        for (String section : sections) {
            String s = section.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() <= size) {
                parts.add(s);
            } else {
                // 单节过长：保留标题语义失败时退化为固定窗口，避免丢内容
                parts.addAll(overflowSplitter.chunk(s));
            }
        }
        return List.copyOf(parts);
    }

    /**
     * 按 H1～H3 标题行切开：每个 section 从标题行起直到下一标题前。
     * 文首无标题的序言单独成段。
     */
    static List<String> splitByHeadings(String text) {
        Matcher m = HEADING_LINE.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        if (starts.isEmpty()) {
            return List.of(text);
        }

        List<String> sections = new ArrayList<>();
        // 标题前的序言（若有）
        if (starts.getFirst() > 0) {
            String preface = text.substring(0, starts.getFirst()).strip();
            if (!preface.isEmpty()) {
                sections.add(preface);
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            sections.add(text.substring(from, to).strip());
        }
        return sections;
    }

    /** 暴露参数便于测试与预览回显。 */
    public int size() {
        return size;
    }

    public int overlap() {
        return overlap;
    }
}
