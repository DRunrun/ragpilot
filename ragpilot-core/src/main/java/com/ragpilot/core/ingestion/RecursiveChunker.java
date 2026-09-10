package com.ragpilot.core.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 递归字符分块器：按分隔符优先级切分，再合并到接近 size。
 *
 * <p>链路位置：ingestion 第二步的可选策略（Admin {@code RECURSIVE}）。
 *
 * <p>为什么需要：FIXED 会硬切断句子；HEADING 依赖 Markdown 结构。
 * RECURSIVE 用「段落 → 行 → 句 → 空格 → 硬切」优先级，尽量在语义边界收口，
 * 与 FIXED 在同文上应产生不同块边界（消融/预览可验证）。
 *
 * <p>算法灵感来自常见 RecursiveCharacterTextSplitter：找第一个能切开文本的分隔符，
 * 递归处理过长片段，再把短片段拼到不超过 size；相邻块带 overlap。
 */
public final class RecursiveChunker implements Chunker {

    /** 默认分隔符：段落、行、中文句号、空格、空串（硬切）。 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。", " ", "");

    private final int size;
    private final int overlap;
    private final List<String> separators;

    /**
     * @param size       目标块长；必须 &gt; 0
     * @param overlap    重叠；取值 {@code [0, size)}
     * @param separators 优先级从高到低；null/空则用 {@link #DEFAULT_SEPARATORS}
     */
    public RecursiveChunker(int size, int overlap, List<String> separators) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap must be in [0, size)");
        }
        this.size = size;
        this.overlap = overlap;
        this.separators = (separators == null || separators.isEmpty())
                ? DEFAULT_SEPARATORS
                : List.copyOf(separators);
    }

    public RecursiveChunker(int size, int overlap) {
        this(size, overlap, DEFAULT_SEPARATORS);
    }

    @Override
    public List<String> chunk(String text) {
        Objects.requireNonNull(text, "text");
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.copyOf(splitRecursive(normalized, separators));
    }

    private List<String> splitRecursive(String text, List<String> seps) {
        if (text.length() <= size) {
            return List.of(text);
        }
        if (seps.isEmpty()) {
            return hardSplit(text);
        }

        String sep = seps.getFirst();
        List<String> rest = seps.subList(1, seps.size());

        // 空分隔符 = 按字符硬切（最后手段）
        if (sep.isEmpty()) {
            return hardSplit(text);
        }
        // 当前分隔符不存在：降级到下一优先级
        if (!text.contains(sep)) {
            return splitRecursive(text, rest);
        }

        String[] rawParts = text.split(PatternQuote.quote(sep), -1);
        List<String> pieces = new ArrayList<>();
        for (String raw : rawParts) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            if (raw.length() <= size) {
                pieces.add(raw);
            } else {
                pieces.addAll(splitRecursive(raw, rest));
            }
        }
        return mergeWithOverlap(pieces);
    }

    /** 把短片段拼到不超过 size；跨块保留 overlap。 */
    private List<String> mergeWithOverlap(List<String> pieces) {
        if (pieces.isEmpty()) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String piece : pieces) {
            if (buf.isEmpty()) {
                buf.append(piece);
                continue;
            }
            // 合并时用空格/换行的近似：原文已在 split 时丢掉 sep，用空格粘合可读性更好
            int projected = buf.length() + 1 + piece.length();
            if (projected <= size) {
                buf.append(' ').append(piece);
            } else {
                merged.add(buf.toString());
                // overlap：从上一块尾巴取 overlap 字符作为下一块前缀
                String prev = buf.toString();
                buf.setLength(0);
                if (overlap > 0 && prev.length() > overlap) {
                    buf.append(prev.substring(prev.length() - overlap));
                    buf.append(' ');
                } else if (overlap > 0) {
                    buf.append(prev).append(' ');
                }
                buf.append(piece);
                // 若加 overlap 后仍超长，先吐出再硬切本片
                if (buf.length() > size) {
                    String overflow = buf.toString();
                    buf.setLength(0);
                    merged.addAll(hardSplit(overflow));
                }
            }
        }
        if (!buf.isEmpty()) {
            merged.add(buf.toString());
        }
        return merged;
    }

    private List<String> hardSplit(String text) {
        return new FixedSizeChunker(size, overlap).chunk(text);
    }

    public int size() {
        return size;
    }

    public int overlap() {
        return overlap;
    }

    public List<String> separators() {
        return separators;
    }

    /**
     * 对字面分隔符做 Pattern.quote 的薄封装，避免误把 {@code \n} 当正则。
     * split 用字面量：Java {@link String#split(String)} 把参数当正则，必须 quote。
     */
    private static final class PatternQuote {
        static String quote(String sep) {
            return java.util.regex.Pattern.quote(sep);
        }
    }
}
