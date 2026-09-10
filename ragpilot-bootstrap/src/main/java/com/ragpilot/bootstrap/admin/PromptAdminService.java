package com.ragpilot.bootstrap.admin;

import com.ragpilot.ops.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 管理：列表/详情/激活/回滚/自定义版本持久化（ADM-1.1～1.2）。
 *
 * <p>链路位置：Admin → Registry（内存）+ prompt_template / runtime_config（DB）。
 */
@Service
@Order(85)
public class PromptAdminService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptAdminService.class);

    private final PromptRegistry promptRegistry;
    private final RuntimeConfigService runtimeConfigService;
    private final JdbcTemplate jdbcTemplate;

    public PromptAdminService(
            PromptRegistry promptRegistry,
            RuntimeConfigService runtimeConfigService,
            JdbcTemplate jdbcTemplate
    ) {
        this.promptRegistry = promptRegistry;
        this.runtimeConfigService = runtimeConfigService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 等 Admin schema 初始化后再加载自定义模板（Order 85 > 80）。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT version, content FROM prompt_template");
            for (Map<String, Object> row : rows) {
                promptRegistry.register(
                        String.valueOf(row.get("version")),
                        String.valueOf(row.get("content")));
            }
            runtimeConfigService.promptActiveVersion().ifPresent(v -> {
                try {
                    promptRegistry.restoreActive(v);
                } catch (Exception e) {
                    log.warn("Skip restoring prompt active version {}: {}", v, e.toString());
                }
            });
            log.info("Loaded {} custom prompt templates from DB", rows.size());
        } catch (Exception e) {
            log.warn("Skip loading prompt_template: {}", e.toString());
        }
    }

    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        String active = promptRegistry.activeVersion();
        for (String v : promptRegistry.versions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", v);
            item.put("active", v.equals(active));
            item.put("length", promptRegistry.template(v).length());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", active);
        data.put("historySize", promptRegistry.historySize());
        data.put("items", items);
        data.put("total", items.size());
        return data;
    }

    public Map<String, Object> get(String version) {
        String content = promptRegistry.template(version);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("content", content);
        data.put("active", version.equals(promptRegistry.activeVersion()));
        return data;
    }

    public Map<String, Object> activate(String version) {
        promptRegistry.activate(version);
        runtimeConfigService.put(RuntimeConfigKeys.PROMPT_ACTIVE, version);
        return list();
    }

    public Map<String, Object> rollback() {
        String v = promptRegistry.rollback();
        runtimeConfigService.put(RuntimeConfigKeys.PROMPT_ACTIVE, v);
        return list();
    }

    public Map<String, Object> upsert(String version, String content) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        String v = version.strip();
        promptRegistry.register(v, content);
        jdbcTemplate.update(
                """
                        INSERT INTO prompt_template(version, content, source, updated_at)
                        VALUES (?, ?, 'custom', NOW())
                        ON CONFLICT (version) DO UPDATE
                        SET content = EXCLUDED.content, updated_at = NOW()
                        """,
                v, content);
        return get(v);
    }

    public Map<String, Object> diff(String left, String right) {
        String a = promptRegistry.template(left);
        String b = promptRegistry.template(right);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("left", left);
        data.put("right", right);
        data.put("leftContent", a);
        data.put("rightContent", b);
        data.put("leftLines", a.split("\\R", -1));
        data.put("rightLines", b.split("\\R", -1));
        return data;
    }
}
