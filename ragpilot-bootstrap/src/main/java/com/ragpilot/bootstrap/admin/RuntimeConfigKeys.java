package com.ragpilot.bootstrap.admin;

import java.util.Set;

/**
 * 允许写入 DB 覆盖层的配置键白名单（ADM-0.6 / admin-console-spec §4.3；P1 扩展 ADM-6～9）。
 */
public final class RuntimeConfigKeys {

    public static final String CHUNK_STRATEGY = "ragpilot.chunk.strategy";
    public static final String CHUNK_SIZE = "ragpilot.chunk.size";
    public static final String CHUNK_OVERLAP = "ragpilot.chunk.overlap";
    public static final String CHUNK_SEPARATORS = "ragpilot.chunk.separators";
    public static final String PROMPT_ACTIVE = "ragpilot.prompt.active-version";
    public static final String RETRIEVAL_MODE = "ragpilot.retrieval.mode";
    public static final String RETRIEVAL_TOP_K = "ragpilot.retrieval.top-k";
    public static final String RETRIEVAL_MIN_SCORE = "ragpilot.retrieval.min-score";
    public static final String DEFAULT_KB = "ragpilot.retrieval.default-knowledge-base-id";

    // ADM-6 模型
    public static final String LLM_BASE_URL = "ragpilot.llm.base-url";
    public static final String CHAT_MODEL = "ragpilot.generation.model";
    public static final String CHAT_TEMPERATURE = "ragpilot.generation.temperature";
    public static final String EMBEDDING_MODEL = "ragpilot.embedding.model";
    public static final String EMBEDDING_DIM = "ragpilot.embedding.dimensions";
    public static final String RERANK_ENABLED = "ragpilot.retrieval.rerank.enabled";
    public static final String RERANK_MODEL = "ragpilot.retrieval.rerank.model";
    public static final String RERANK_BASE_URL = "ragpilot.retrieval.rerank.base-url";

    // ADM-7 Token
    public static final String TOKEN_PROMPT_PRICE = "ragpilot.token.prompt-price-per-1k";
    public static final String TOKEN_COMPLETION_PRICE = "ragpilot.token.completion-price-per-1k";

    // ADM-9 Agent / MCP
    public static final String AGENT_MAX_STEPS = "ragpilot.agent.max-steps";
    public static final String AGENT_STEP_TIMEOUT_MS = "ragpilot.agent.step-timeout-ms";
    public static final String AGENT_HTTP_ALLOWLIST = "ragpilot.agent.http-allowlist";
    public static final String AGENT_HTTP_TIMEOUT_MS = "ragpilot.agent.http-timeout-ms";
    public static final String MCP_ENABLED = "ragpilot.mcp.enabled";
    public static final String MCP_ENDPOINT = "ragpilot.mcp.endpoint";
    public static final String MCP_TIMEOUT_MS = "ragpilot.mcp.timeout-ms";

    /** 变更后建议重建向量库的危险键。 */
    public static final Set<String> DANGEROUS = Set.of(EMBEDDING_MODEL, EMBEDDING_DIM);

    public static final Set<String> ALLOWED = Set.of(
            CHUNK_STRATEGY,
            CHUNK_SIZE,
            CHUNK_OVERLAP,
            CHUNK_SEPARATORS,
            PROMPT_ACTIVE,
            RETRIEVAL_MODE,
            RETRIEVAL_TOP_K,
            RETRIEVAL_MIN_SCORE,
            DEFAULT_KB,
            LLM_BASE_URL,
            CHAT_MODEL,
            CHAT_TEMPERATURE,
            EMBEDDING_MODEL,
            EMBEDDING_DIM,
            RERANK_ENABLED,
            RERANK_MODEL,
            RERANK_BASE_URL,
            TOKEN_PROMPT_PRICE,
            TOKEN_COMPLETION_PRICE,
            AGENT_MAX_STEPS,
            AGENT_STEP_TIMEOUT_MS,
            AGENT_HTTP_ALLOWLIST,
            AGENT_HTTP_TIMEOUT_MS,
            MCP_ENABLED,
            MCP_ENDPOINT,
            MCP_TIMEOUT_MS
    );

    private RuntimeConfigKeys() {
    }

    public static boolean isAllowed(String key) {
        return key != null && ALLOWED.contains(key);
    }

    public static boolean isDangerous(String key) {
        return key != null && DANGEROUS.contains(key);
    }
}
