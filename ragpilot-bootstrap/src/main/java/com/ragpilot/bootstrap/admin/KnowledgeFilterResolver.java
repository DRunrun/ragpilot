package com.ragpilot.bootstrap.admin;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 解析 ask 的知识库过滤范围（ADM-3.5）。
 *
 * <p>规则：显式 knowledgeBaseId &gt; Overlay 默认库 &gt; 全部 enabled 库。
 */
@Component
public class KnowledgeFilterResolver {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RuntimeConfigService runtimeConfigService;

    public KnowledgeFilterResolver(
            KnowledgeBaseService knowledgeBaseService,
            RuntimeConfigService runtimeConfigService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * @param explicitAskKb ask 请求上的 knowledgeBaseId，可空
     * @return 允许命中的库 ID；空列表表示「不限制」（兼容迁移前无 metadata 的旧块）
     */
    public List<String> resolveAllowedKbIds(String explicitAskKb) {
        if (explicitAskKb != null && !explicitAskKb.isBlank()) {
            return List.of(explicitAskKb.strip());
        }
        Optional<String> defaultKb = runtimeConfigService.defaultKnowledgeBaseId();
        if (defaultKb.isPresent()) {
            return List.of(defaultKb.get());
        }
        List<String> enabled = knowledgeBaseService.listEnabledIds();
        // 无启用库时返回空列表 → 检索侧不拼过滤（避免全拒）
        return enabled;
    }

    /** Spring AI filterExpression；无限制时返回 null。 */
    public String toFilterExpression(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        if (kbIds.size() == 1) {
            return "knowledgeBaseId == '" + escape(kbIds.getFirst()) + "'";
        }
        String in = kbIds.stream()
                .map(id -> "'" + escape(id) + "'")
                .collect(Collectors.joining(", "));
        return "knowledgeBaseId in [" + in + "]";
    }

    /** SQL 片段：AND (metadata->>'knowledgeBaseId' = ANY(?))；无限制返回 null。 */
    public String toSqlInClause(List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        return "metadata->>'knowledgeBaseId' = ANY(?)";
    }

    private static String escape(String id) {
        return id.replace("'", "''");
    }
}
