package com.ragpilot.core.generation;

import com.ragpilot.core.domain.RetrievedChunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * RAG Prompt 构建器：把「检索命中 + 用户问题」组装成发给 LLM 的最终 Prompt。
 *
 * <p>链路位置：问答链路第三环（检索 → <b>Prompt 组装</b> → 生成）。
 *
 * <p>F4.1：可绑定版本供应商（来自 ops {@code PromptRegistry}），每次 {@link #build}
 * 使用当前激活模板，并在 {@link #version()} 暴露版本号供 Trace / done 记录。
 */
public final class PromptBuilder {

    /** 无 Registry 时的默认版本号（classpath {@code prompts/rag-v1.txt}）。 */
    public static final String PROMPT_VERSION = "rag-v1";

    private static final String TEMPLATE_PATH = "prompts/" + PROMPT_VERSION + ".txt";
    private static final String CONTEXT_BLOCK_FORMAT = "[%d] source=%s chunkId=%s%n%s";

    private final Supplier<String> templateSupplier;
    private final Supplier<String> versionSupplier;

    /** 用内置 rag-v1 模板构造（单测 / 无 Registry 场景）。 */
    public PromptBuilder() {
        String template = loadDefaultTemplate();
        this.templateSupplier = () -> template;
        this.versionSupplier = () -> PROMPT_VERSION;
    }

    /**
     * 固定模板 + 版本（测试或一次性快照）。
     *
     * @param template 必须含 {context} / {question}
     * @param version  版本号字符串
     */
    public PromptBuilder(String template, String version) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(version, "version");
        if (!template.contains("{context}") || !template.contains("{question}")) {
            throw new IllegalArgumentException("template must contain {context} and {question} placeholders");
        }
        this.templateSupplier = () -> template;
        this.versionSupplier = () -> version;
    }

    /**
     * 兼容旧构造：仅模板，版本记为 {@link #PROMPT_VERSION}。
     */
    public PromptBuilder(String template) {
        this(template, PROMPT_VERSION);
    }

    /**
     * 绑定动态版本源（F4.1 Registry）。
     *
     * @param templateSupplier 每次 build 取当前模板
     * @param versionSupplier  每次取当前版本号
     */
    public PromptBuilder(Supplier<String> templateSupplier, Supplier<String> versionSupplier) {
        this.templateSupplier = Objects.requireNonNull(templateSupplier, "templateSupplier");
        this.versionSupplier = Objects.requireNonNull(versionSupplier, "versionSupplier");
    }

    /** 当前 Prompt 版本号（可能随 Registry 切换而变）。 */
    public String version() {
        return versionSupplier.get();
    }

    /**
     * 组装 Prompt。
     *
     * @param question 用户问题，不允许空白
     * @param hits     检索命中列表，已按分数降序
     * @return 组装好的 Prompt；hits 为空时返回 Optional.empty()
     */
    public Optional<String> build(String question, List<RetrievedChunk> hits) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (hits == null || hits.isEmpty()) {
            return Optional.empty();
        }

        String template = templateSupplier.get();
        if (template == null || !template.contains("{context}") || !template.contains("{question}")) {
            throw new IllegalStateException("active prompt template invalid");
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            context.append(CONTEXT_BLOCK_FORMAT.formatted(
                    i + 1,
                    hit.chunk().docId(),
                    hit.chunk().id(),
                    hit.chunk().content()
            ));
            if (i < hits.size() - 1) {
                context.append("\n\n");
            }
        }

        String prompt = template
                .replace("{context}", context.toString())
                .replace("{question}", question.strip());
        return Optional.of(prompt);
    }

    private static String loadDefaultTemplate() {
        try (InputStream in = PromptBuilder.class.getClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("prompt template not found on classpath: " + TEMPLATE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt template: " + TEMPLATE_PATH, e);
        }
    }
}
