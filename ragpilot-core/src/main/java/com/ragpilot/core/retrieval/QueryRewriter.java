package com.ragpilot.core.retrieval;

import com.ragpilot.core.generation.Generator;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多轮检索的查询改写器：把带指代/省略的当前问题改写成独立检索问句。
 *
 * <p>链路位置：问答链路检索之前（多轮会话场景），消费对话历史 + 当前问题，
 * 产出一条「自含」的 standalone question 交给 {@link Retriever}。
 *
 * <p>为什么不用「拼历史文本去检索」：embedding 模型对长拼接文本语义稀释严重，
 * 「它怎么配置缓存」+ 大段历史 → 向量方向漂移，召回质量反而比裸问题更差；
 * 裸问题又因缺失指代对象召回不到正确内容。让 LLM 先补全指代再检索，
 * 是业界多轮 RAG 的通行做法（query rewriting / condense question）。
 *
 * <p>失败口径：改写调 LLM，就有超时/挂掉/输出不合法的可能。
 * 任何异常都<b>回退原始问题</b>并在结果里带原因——检索链路绝不因改写失败而中断，
 * 宁可回到改写前的行为，也不给空结果。
 */
public final class QueryRewriter {

    /** 改写输出超过该长度视为模型没按约束回答（输出了解释段落），直接回退。 */
    static final int MAX_REWRITE_LENGTH = 200;

    /** 回退原因：无历史（首轮），不发 LLM 调用。 */
    public static final String FALLBACK_NO_HISTORY = "NO_HISTORY";
    /** 回退原因：模型输出为空。 */
    public static final String FALLBACK_EMPTY_OUTPUT = "EMPTY_OUTPUT";
    /** 回退原因：改写结果与原问题一致（本就独立），未发生实质改写。 */
    public static final String FALLBACK_UNCHANGED = "UNCHANGED";
    /** 回退原因：输出过长，不像独立问句。 */
    public static final String FALLBACK_TOO_LONG = "TOO_LONG";
    /** 回退原因：改写超时或生成异常。 */
    public static final String FALLBACK_ERROR = "ERROR";

    private final Generator generator;
    private final Duration timeout;

    /**
     * @param generator chat 生成端口（与主链路共用，热切换模型这里同步生效）
     * @param timeout   改写整体超时；到点回退原文，避免拖垮检索首包延迟
     */
    public QueryRewriter(Generator generator, Duration timeout) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * 改写结果。
     *
     * @param query          实际应去检索的问句（改写成功为新问句，失败为原问题）
     * @param rewritten      是否真正发生了改写（模型被调用且采纳）
     * @param durationMs     改写耗时（未调用模型时为 0）
     * @param fallbackReason 回退原因；改写成功为 null
     */
    public record RewriteResult(
            String query,
            boolean rewritten,
            long durationMs,
            String fallbackReason
    ) {
    }

    /**
     * 结合历史改写当前问题。
     *
     * @param historyText 最近若干轮的纯文本历史（每行 {@code role: content}）；
     *                    空/null 视为首轮——不发 LLM 调用，直接用原问题
     * @param question    用户当前问题
     * @return 改写结果；任何失败都回退原问题，不抛异常
     */
    public RewriteResult rewrite(String historyText, String question) {
        String original = question == null ? "" : question.strip();
        if (historyText == null || historyText.isBlank() || original.isBlank()) {
            return new RewriteResult(original, false, 0, FALLBACK_NO_HISTORY);
        }

        long t0 = System.currentTimeMillis();
        AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());
        try {
            String prompt = buildPrompt(historyText, original);
            List<String> tokens = generator.streamTokens(prompt)
                    .timeout(timeout)
                    .doOnNext(t -> buffer.get().append(t))
                    .collectList()
                    .block(timeout.plusSeconds(1)); // timeout 已在 Flux 上，这里只是 block 兜底

            String out = sanitize(buffer.get().toString());
            if (out.isEmpty()) {
                return new RewriteResult(original, false, elapsed(t0), FALLBACK_EMPTY_OUTPUT);
            }
            if (out.length() > MAX_REWRITE_LENGTH) {
                return new RewriteResult(original, false, elapsed(t0), FALLBACK_TOO_LONG);
            }
            boolean changed = !out.equals(original);
            return new RewriteResult(out, changed, elapsed(t0),
                    changed ? null : FALLBACK_UNCHANGED);
        } catch (Exception e) {
            // 超时 / 生成服务异常：回退原文，检索链路继续（口径与 F4.2 降级一致）
            return new RewriteResult(original, false, elapsed(t0),
                    FALLBACK_ERROR + ": " + e.getClass().getSimpleName());
        }
    }

    private long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }

    /** 改写提示词：只输出一行独立问句，禁止回答问题本身。 */
    private static String buildPrompt(String historyText, String question) {
        return """
                你是检索查询改写器。根据对话历史，把「当前问题」改写成一个不依赖历史、\
                语义完整的独立问题，用于知识库检索。
                规则：只输出改写后的问题本身，一行，不要编号、引号、前缀或解释；\
                若当前问题本身已独立完整，则原样输出。

                对话历史：
                %s

                当前问题：%s
                独立问题：""".formatted(historyText, question);
    }

    /**
     * 清洗模型输出：取第一行非空内容，剥掉「标签：正文」式前缀与包裹引号。
     * 思考型模型偶尔多输出说明文字，第一行通常就是改写结果。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        for (String line : raw.split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) {
                continue;
            }
            // 「标签：正文」形式——冒号前是短标签（改写/独立问题一类）则只取正文；
            // 用冒号定位比逐个剥前缀稳，能覆盖「改写后的问题：」「「独立问题」：」等变体
            int colon = firstColon(s);
            if (colon > 0 && colon <= 8) {
                String label = s.substring(0, colon).replaceAll("[「」\"'\\s]", "");
                for (String prefix : REWRITE_LABELS) {
                    if (label.startsWith(prefix)) {
                        s = s.substring(colon + 1).strip();
                        break;
                    }
                }
            }
            // 剥残留的引导引号/括号（如「改写：」后的正文仍带前引号）与成对包裹引号
            s = s.replaceAll("^[」\"“”']+", "").strip();
            if (s.length() >= 2
                    && (s.startsWith("\"") || s.startsWith("「") || s.startsWith("\u201c"))
                    && (s.endsWith("\"") || s.endsWith("」") || s.endsWith("\u201d"))) {
                s = s.substring(1, s.length() - 1).strip();
            }
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /** 识别为「标签」的前缀词。 */
    private static final String[] REWRITE_LABELS = {"改写", "重写", "独立问题", "检索问题", "优化后问题"};

    /** 第一个中文或英文冒号的位置（跳过开头引号）。 */
    private static int firstColon(String s) {
        int z = s.indexOf('：');
        int e = s.indexOf(':');
        if (z < 0) {
            return e;
        }
        return e < 0 ? z : Math.min(z, e);
    }
}
