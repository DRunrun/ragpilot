package com.ragpilot.ops.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prompt 版本注册表：加载、切换、回滚模板版本。
 *
 * <p>链路位置：ops 可观测 / 配置层（F4.1）；生成前由 bootstrap 取 {@link #activeTemplate()}，
 * 每次 ask 在 Trace / done 事件里记录 {@link #activeVersion()}，评测可按版本对比。
 * <p>约定：classpath {@code prompts/rag-*.txt}，文件名（去扩展名）即版本号。
 */
public final class PromptRegistry {

    /** 默认激活版本（与历史 M1 模板对齐）。 */
    public static final String DEFAULT_VERSION = "rag-v1";

    /** 可变：Admin 可注册 DB 自定义版本（ADM-1.2），classpath 种子仍先加载。 */
    private final ConcurrentHashMap<String, String> templates = new ConcurrentHashMap<>();
    private final AtomicReference<String> active = new AtomicReference<>();
    /** 回滚栈：每次 activate 把旧版本压栈，rollback() 弹出。 */
    private final Deque<String> history = new ArrayDeque<>();

    /**
     * @param templates 版本 → 模板全文；不可为空，且必须含 active 初始版本
     * @param activeVersion 初始激活版本
     */
    public PromptRegistry(Map<String, String> templates, String activeVersion) {
        Objects.requireNonNull(templates, "templates");
        if (templates.isEmpty()) {
            throw new IllegalArgumentException("templates must not be empty");
        }
        templates.forEach(this::putValidated);
        activate(activeVersion == null ? DEFAULT_VERSION : activeVersion);
        // 初始激活不记历史，避免误滚到 null
        history.clear();
    }

    /**
     * 注册或覆盖一个版本（Admin 新建/编辑自定义模板）。
     *
     * @param version  版本号，建议 {@code rag-custom-*} 或 {@code rag-vN}
     * @param template 须含 {@code {context}} 与 {@code {question}}
     */
    public synchronized void register(String version, String template) {
        putValidated(version, template);
    }

    private void putValidated(String version, String template) {
        if (version == null || version.isBlank() || template == null) {
            throw new IllegalArgumentException("invalid template entry");
        }
        if (!template.contains("{context}") || !template.contains("{question}")) {
            throw new IllegalArgumentException("template " + version + " missing placeholders");
        }
        this.templates.put(version.strip(), template);
    }

    /**
     * 从 classpath 目录加载 {@code prompts/rag-*.txt}（通过已知文件名列表）。
     *
     * <p>不用扫 jar 目录（不可靠），显式枚举版本文件。
     */
    public static PromptRegistry loadDefault(String activeVersion) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String version : List.of("rag-v1", "rag-v2")) {
            String path = "prompts/" + version + ".txt";
            URL url = PromptRegistry.class.getClassLoader().getResource(path);
            if (url == null) {
                continue;
            }
            try (InputStream in = url.openStream()) {
                map.put(version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read " + path, e);
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("no prompt templates found under classpath:prompts/");
        }
        String active = activeVersion;
        if (active == null || !map.containsKey(active)) {
            active = map.containsKey(DEFAULT_VERSION) ? DEFAULT_VERSION : map.keySet().iterator().next();
        }
        return new PromptRegistry(map, active);
    }

    /** 已注册版本列表（按名称排序，稳定展示）。 */
    public List<String> versions() {
        return templates.keySet().stream().sorted().toList();
    }

    public String activeVersion() {
        return active.get();
    }

    public String activeTemplate() {
        return templates.get(active.get());
    }

    public String template(String version) {
        String t = templates.get(version);
        if (t == null) {
            throw new IllegalArgumentException("unknown prompt version: " + version
                    + "; known=" + versions());
        }
        return t;
    }

    /**
     * 切换激活版本；旧版本入回滚栈。
     *
     * @param version 目标版本，必须已注册
     * @return 切换后的激活版本
     */
    public synchronized String activate(String version) {
        Objects.requireNonNull(version, "version");
        if (!templates.containsKey(version)) {
            throw new IllegalArgumentException("unknown prompt version: " + version
                    + "; known=" + versions());
        }
        String prev = active.get();
        if (prev != null && !prev.equals(version)) {
            history.push(prev);
        }
        active.set(version);
        return version;
    }

    /**
     * 回滚到上一次激活的版本。
     *
     * @return 回滚后的版本
     * @throws IllegalStateException 没有可回滚历史时
     */
    public synchronized String rollback() {
        if (history.isEmpty()) {
            throw new IllegalStateException("no prompt version to rollback");
        }
        String prev = history.pop();
        active.set(prev);
        return prev;
    }

    /**
     * 启动恢复激活版本：不写入回滚栈、并清空历史（避免误滚到启动前状态）。
     */
    public synchronized void restoreActive(String version) {
        Objects.requireNonNull(version, "version");
        if (!templates.containsKey(version)) {
            throw new IllegalArgumentException("unknown prompt version: " + version
                    + "; known=" + versions());
        }
        active.set(version);
        history.clear();
    }

    /** 回滚历史深度（测试用）。 */
    public synchronized int historySize() {
        return history.size();
    }

    /** 便于测试：当前历史快照。 */
    public synchronized List<String> historySnapshot() {
        return new ArrayList<>(history);
    }
}
