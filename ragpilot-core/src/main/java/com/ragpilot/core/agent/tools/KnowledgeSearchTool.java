package com.ragpilot.core.agent.tools;

import com.ragpilot.core.agent.Tool;
import com.ragpilot.core.agent.ToolObservation;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.Retriever;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库检索工具：把 M2 {@link Retriever} 暴露给 ReAct Agent。
 *
 * <p>链路位置：Agent ACT 阶段最常用的第一工具——「先查库再作答」。
 * <p>为什么存在：让 Agent 自主决定何时检索，而不是编排层写死「总是先 retrieve」。
 */
public final class KnowledgeSearchTool implements Tool {

    public static final String NAME = "knowledge_search";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "检索查询词或用户问题" },
                "topK": { "type": "integer", "description": "返回条数，默认用构造参数" }
              },
              "required": ["query"]
            }
            """;

    private static final Pattern QUERY_JSON = Pattern.compile(
            "\"query\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern TOP_K_JSON = Pattern.compile("\"topK\"\\s*:\\s*(\\d+)");

    private final Retriever retriever;
    private final int defaultTopK;

    /**
     * @param retriever    M2 检索实现（向量 / 混合均可）
     * @param defaultTopK  未指定 topK 时的默认条数
     */
    public KnowledgeSearchTool(Retriever retriever, int defaultTopK) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        if (defaultTopK <= 0) {
            throw new IllegalArgumentException("defaultTopK must be > 0");
        }
        this.defaultTopK = defaultTopK;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the private knowledge base (Spring docs / ingested corpus) "
                + "for passages relevant to a query. Use before answering factual questions.";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    /**
     * 执行检索（纯文本视图）。
     *
     * @param input JSON {@code {"query":"...","topK":5}} 或纯文本查询
     * @return 可读的命中摘要；无命中返回固定提示
     */
    @Override
    public String execute(String input) throws Exception {
        return observe(input).text();
    }

    /**
     * 结构化执行：把命中块随观察结果带出，供答案合成复用、避免二次检索。
     *
     * @param input JSON 或纯文本查询
     * @return 文本摘要 + 命中块（无命中时 chunks 为空列表）
     */
    @Override
    public ToolObservation observe(String input) {
        Parsed parsed = parse(input);
        List<RetrievedChunk> hits = retriever.retrieve(parsed.query(), parsed.topK());
        if (hits == null || hits.isEmpty()) {
            return new ToolObservation(
                    "NO_HITS: knowledge base returned no passages for query=" + parsed.query(),
                    List.of());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HITS=").append(hits.size()).append('\n');
        int i = 1;
        for (RetrievedChunk hit : hits) {
            String content = hit.chunk().content();
            String snippet = content == null ? ""
                    : content.substring(0, Math.min(content.length(), 240));
            sb.append('[').append(i++).append("] docId=").append(hit.chunk().docId())
                    .append(" chunkId=").append(hit.chunk().id())
                    .append(" score=").append(String.format(java.util.Locale.ROOT, "%.4f", hit.score()))
                    .append('\n').append(snippet).append('\n');
        }
        return new ToolObservation(sb.toString(), hits);
    }

    private Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("knowledge_search input must not be blank");
        }
        String stripped = input.strip();
        if (stripped.startsWith("{")) {
            Matcher qm = QUERY_JSON.matcher(stripped);
            if (!qm.find()) {
                throw new IllegalArgumentException("knowledge_search JSON missing query");
            }
            String query = unescape(qm.group(1));
            int topK = defaultTopK;
            Matcher tm = TOP_K_JSON.matcher(stripped);
            if (tm.find()) {
                topK = Integer.parseInt(tm.group(1));
            }
            return new Parsed(query, Math.max(topK, 1));
        }
        return new Parsed(stripped, defaultTopK);
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private record Parsed(String query, int topK) {
    }
}
