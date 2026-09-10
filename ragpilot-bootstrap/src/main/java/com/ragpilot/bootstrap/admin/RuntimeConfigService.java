package com.ragpilot.bootstrap.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.config.RagPilotProperties;
import com.ragpilot.core.retrieval.RetrievalMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 运行时配置覆盖层：yml 默认 ←合并← DB（ADM-0.6 / P1 扩展）。
 *
 * <p>链路位置：Admin 改配置后写入 {@code ragpilot_runtime_config}，内存视图立即刷新；
 * 禁止改仓库 application.yml。
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RagPilotProperties defaults;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.base-url:http://127.0.0.1:1234}")
    private String defaultBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String defaultChatModelYml;

    @Value("${ragpilot.token.prompt-price-per-1k:0}")
    private double defaultPromptPrice;

    @Value("${ragpilot.token.completion-price-per-1k:0}")
    private double defaultCompletionPrice;

    @Value("${ragpilot.agent.max-steps:5}")
    private int defaultMaxSteps;

    @Value("${ragpilot.agent.step-timeout-ms:15000}")
    private long defaultStepTimeoutMs;

    @Value("${ragpilot.agent.http-allowlist:docs.spring.io,127.0.0.1}")
    private String defaultHttpAllowlist;

    @Value("${ragpilot.agent.http-timeout-ms:5000}")
    private long defaultHttpTimeoutMs;

    @Value("${ragpilot.mcp.enabled:false}")
    private boolean defaultMcpEnabled;

    @Value("${ragpilot.mcp.endpoint:http://127.0.0.1:3100/mcp}")
    private String defaultMcpEndpoint;

    @Value("${ragpilot.mcp.timeout-ms:5000}")
    private long defaultMcpTimeoutMs;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:768}")
    private int defaultEmbeddingDim;

    /** key → JSON/标量字符串 */
    private final ConcurrentHashMap<String, String> overlay = new ConcurrentHashMap<>();

    public RuntimeConfigService(
            JdbcTemplate jdbcTemplate,
            RagPilotProperties defaults,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaults = defaults;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadFromDb() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_key, config_value FROM ragpilot_runtime_config");
            overlay.clear();
            for (Map<String, Object> row : rows) {
                overlay.put(String.valueOf(row.get("config_key")), String.valueOf(row.get("config_value")));
            }
            log.info("Loaded {} runtime config overlay keys", overlay.size());
        } catch (Exception e) {
            log.warn("Runtime config table not ready yet: {}", e.toString());
        }
    }

    public Map<String, String> snapshotOverlay() {
        return Map.copyOf(overlay);
    }

    /** 生效视图（脱敏后的可读配置，供 Admin 展示）。 */
    public Map<String, Object> effectiveView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("chunk.strategy", chunkStrategy());
        view.put("chunk.size", chunkSize());
        view.put("chunk.overlap", chunkOverlap());
        view.put("chunk.separators", chunkSeparators());
        view.put("prompt.activeVersion", promptActiveVersion().orElse(null));
        view.put("retrieval.mode", retrievalMode().name());
        view.put("retrieval.topK", topK());
        view.put("retrieval.minScore", minScore());
        view.put("retrieval.defaultKnowledgeBaseId", defaultKnowledgeBaseId().orElse(null));
        view.put("llm.baseUrl", llmBaseUrl());
        view.put("generation.model", chatModel());
        view.put("generation.temperature", chatTemperature());
        view.put("embedding.model", embeddingModel());
        view.put("embedding.dimensions", embeddingDimensions());
        view.put("retrieval.rerank.enabled", rerankEnabled());
        view.put("retrieval.rerank.model", rerankModel());
        view.put("retrieval.rerank.baseUrl", rerankBaseUrl());
        view.put("token.promptPricePer1k", promptPricePer1k());
        view.put("token.completionPricePer1k", completionPricePer1k());
        view.put("agent.maxSteps", agentMaxSteps());
        view.put("agent.stepTimeoutMs", agentStepTimeoutMs());
        view.put("agent.httpAllowlist", agentHttpAllowlist());
        view.put("agent.httpTimeoutMs", agentHttpTimeoutMs());
        view.put("mcp.enabled", mcpEnabled());
        view.put("mcp.endpoint", mcpEndpoint());
        view.put("mcp.timeoutMs", mcpTimeoutMs());
        view.put("overlayKeys", overlay.keySet());
        return view;
    }

    /**
     * @return 危险键变更时的中文警告列表（可空）
     */
    public List<String> put(String key, String value) {
        if (!RuntimeConfigKeys.isAllowed(key)) {
            throw new IllegalArgumentException("CONFIG_KEY_NOT_ALLOWED: " + key);
        }
        jdbcTemplate.update(
                """
                        INSERT INTO ragpilot_runtime_config(config_key, config_value, updated_at, updated_by)
                        VALUES (?, ?, NOW(), 'local')
                        ON CONFLICT (config_key) DO UPDATE
                        SET config_value = EXCLUDED.config_value,
                            updated_at = NOW(),
                            updated_by = 'local'
                        """,
                key, value);
        overlay.put(key, value);
        if (RuntimeConfigKeys.isDangerous(key)) {
            return List.of("该键影响 Embedding / 向量维度，修改后需重建向量库并重启应用。");
        }
        if (RuntimeConfigKeys.LLM_BASE_URL.equals(key)
                || RuntimeConfigKeys.CHAT_MODEL.equals(key)
                || RuntimeConfigKeys.EMBEDDING_MODEL.equals(key)) {
            return List.of("Spring AI 客户端在启动时绑定模型；改 baseUrl/model 后需重启 bootstrap 才全面生效（探测接口可立即用 Overlay）。");
        }
        if (RuntimeConfigKeys.MCP_ENABLED.equals(key)) {
            return List.of("MCP 启用状态影响 Bean 装配，修改后需重启应用。");
        }
        if (RuntimeConfigKeys.AGENT_MAX_STEPS.equals(key)
                || RuntimeConfigKeys.AGENT_STEP_TIMEOUT_MS.equals(key)) {
            return List.of("Agent maxSteps / stepTimeout 在 Bean 构造时注入，修改后需重启生效。");
        }
        return List.of();
    }

    public void delete(String key) {
        if (!RuntimeConfigKeys.isAllowed(key)) {
            throw new IllegalArgumentException("CONFIG_KEY_NOT_ALLOWED: " + key);
        }
        jdbcTemplate.update("DELETE FROM ragpilot_runtime_config WHERE config_key = ?", key);
        overlay.remove(key);
    }

    public String chunkStrategy() {
        return overlay.getOrDefault(RuntimeConfigKeys.CHUNK_STRATEGY, "FIXED");
    }

    public int chunkSize() {
        return intOr(RuntimeConfigKeys.CHUNK_SIZE, defaults.chunk().size());
    }

    public int chunkOverlap() {
        return intOr(RuntimeConfigKeys.CHUNK_OVERLAP, defaults.chunk().overlap());
    }

    public List<String> chunkSeparators() {
        String raw = overlay.get(RuntimeConfigKeys.CHUNK_SEPARATORS);
        if (raw == null || raw.isBlank()) {
            return List.of("\n\n", "\n", "。", " ", "");
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of("\n\n", "\n", "。", " ", "");
        }
    }

    public Optional<String> promptActiveVersion() {
        return Optional.ofNullable(overlay.get(RuntimeConfigKeys.PROMPT_ACTIVE)).filter(s -> !s.isBlank());
    }

    public RetrievalMode retrievalMode() {
        String raw = overlay.get(RuntimeConfigKeys.RETRIEVAL_MODE);
        if (raw == null || raw.isBlank()) {
            return defaults.retrieval().mode();
        }
        try {
            return RetrievalMode.valueOf(raw.strip().toUpperCase());
        } catch (Exception e) {
            return defaults.retrieval().mode();
        }
    }

    public int topK() {
        return intOr(RuntimeConfigKeys.RETRIEVAL_TOP_K, defaults.retrieval().topK());
    }

    public double minScore() {
        return doubleOr(RuntimeConfigKeys.RETRIEVAL_MIN_SCORE, defaults.retrieval().minScore());
    }

    public Optional<String> defaultKnowledgeBaseId() {
        return Optional.ofNullable(overlay.get(RuntimeConfigKeys.DEFAULT_KB)).filter(s -> !s.isBlank());
    }

    public String llmBaseUrl() {
        return stringOr(RuntimeConfigKeys.LLM_BASE_URL, defaultBaseUrl);
    }

    public String chatModel() {
        String yml = defaultChatModelYml == null || defaultChatModelYml.isBlank()
                ? defaults.generation().model()
                : defaultChatModelYml;
        return stringOr(RuntimeConfigKeys.CHAT_MODEL, yml);
    }

    public double chatTemperature() {
        return doubleOr(RuntimeConfigKeys.CHAT_TEMPERATURE, defaults.generation().temperature());
    }

    public String embeddingModel() {
        return stringOr(RuntimeConfigKeys.EMBEDDING_MODEL, defaults.embedding().model());
    }

    public int embeddingDimensions() {
        return intOr(RuntimeConfigKeys.EMBEDDING_DIM, defaultEmbeddingDim);
    }

    public boolean rerankEnabled() {
        String raw = overlay.get(RuntimeConfigKeys.RERANK_ENABLED);
        if (raw == null || raw.isBlank()) {
            return Boolean.TRUE.equals(defaults.retrieval().rerank().enabled());
        }
        return Boolean.parseBoolean(raw.strip());
    }

    public String rerankModel() {
        return stringOr(RuntimeConfigKeys.RERANK_MODEL, defaults.retrieval().rerank().model());
    }

    public String rerankBaseUrl() {
        return stringOr(RuntimeConfigKeys.RERANK_BASE_URL, defaults.retrieval().rerank().baseUrl());
    }

    public double promptPricePer1k() {
        return doubleOr(RuntimeConfigKeys.TOKEN_PROMPT_PRICE, defaultPromptPrice);
    }

    public double completionPricePer1k() {
        return doubleOr(RuntimeConfigKeys.TOKEN_COMPLETION_PRICE, defaultCompletionPrice);
    }

    public int agentMaxSteps() {
        return intOr(RuntimeConfigKeys.AGENT_MAX_STEPS, defaultMaxSteps);
    }

    public long agentStepTimeoutMs() {
        return longOr(RuntimeConfigKeys.AGENT_STEP_TIMEOUT_MS, defaultStepTimeoutMs);
    }

    public String agentHttpAllowlist() {
        return stringOr(RuntimeConfigKeys.AGENT_HTTP_ALLOWLIST, defaultHttpAllowlist);
    }

    public Set<String> agentHttpAllowlistSet() {
        return Arrays.stream(agentHttpAllowlist().split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public long agentHttpTimeoutMs() {
        return longOr(RuntimeConfigKeys.AGENT_HTTP_TIMEOUT_MS, defaultHttpTimeoutMs);
    }

    public boolean mcpEnabled() {
        String raw = overlay.get(RuntimeConfigKeys.MCP_ENABLED);
        if (raw == null || raw.isBlank()) {
            return defaultMcpEnabled;
        }
        return Boolean.parseBoolean(raw.strip());
    }

    public String mcpEndpoint() {
        return stringOr(RuntimeConfigKeys.MCP_ENDPOINT, defaultMcpEndpoint);
    }

    public long mcpTimeoutMs() {
        return longOr(RuntimeConfigKeys.MCP_TIMEOUT_MS, defaultMcpTimeoutMs);
    }

    private String stringOr(String key, String fallback) {
        String raw = overlay.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.strip();
    }

    private int intOr(String key, int fallback) {
        String raw = overlay.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long longOr(String key, long fallback) {
        String raw = overlay.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double doubleOr(String key, double fallback) {
        String raw = overlay.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
