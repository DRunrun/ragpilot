package com.ragpilot.core.generation;

import com.ragpilot.core.domain.Citation;
import com.ragpilot.core.domain.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation 组装器：把检索命中变成编号引用，并对答案里的引用标记做软校验。
 *
 * <p>链路位置：问答链路的收尾环节（检索 → 生成 → <b>Citation 组装/校验</b> → 返回）。
 *
 * <p>为什么校验是「软」的：本地小模型偶尔标错引用编号，
 * 这属于质量问题而非系统错误——只记 warn 日志供评测分析，不向用户报错、不阻断返回。
 */
public final class CitationAssembler {

    private static final Logger log = LoggerFactory.getLogger(CitationAssembler.class);

    /** 答案里的引用标记：[1]、[12] 等，只认纯数字方括号。 */
    private static final Pattern CITATION_MARK = Pattern.compile("\\[(\\d+)]");

    /** snippet 截断长度：前端引用卡片展示用，太长会撑爆 UI。 */
    private static final int SNIPPET_MAX_CHARS = 200;

    /**
     * 检索命中 → 编号引用列表。
     *
     * @param hits 检索命中，已按分数降序；编号按此顺序从 1 开始，
     *             与 Prompt 里上下文块编号一致，答案中的 [n] 才能对上
     * @return 引用列表；hits 为空返回空列表
     */
    public List<Citation> assemble(List<RetrievedChunk> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Citation> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            citations.add(new Citation(
                    i + 1,
                    hit.chunk().docId(),
                    hit.chunk().id(),
                    snippetOf(hit.chunk().content())
            ));
        }
        return List.copyOf(citations);
    }

    /**
     * 软校验答案中的引用编号是否越界。
     *
     * <p>越界只产生 warning（记日志 + 返回给调用方做评测统计），不抛异常：
     * 答案本体仍然返回给用户，引用卡片照常展示合法的部分。
     *
     * @param answer    模型生成的答案文本
     * @param citations 本次实际给出的引用列表
     * @return 警告信息列表；无越界时为空列表
     */
    public List<String> validate(String answer, List<Citation> citations) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        int maxIndex = citations == null ? 0 : citations.size();
        List<String> warnings = new ArrayList<>();

        Matcher matcher = CITATION_MARK.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > maxIndex) {
                String warning = "citation index [%d] out of range (1..%d)".formatted(index, maxIndex);
                warnings.add(warning);
                // 软校验：记 warn 供排障与评测，不打断返回
                log.warn("Citation check: {}", warning);
            }
        }
        return List.copyOf(warnings);
    }

    /** 截出可展示的原文片段：超长截断并加省略号，空白压成单个空格。 */
    private static String snippetOf(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= SNIPPET_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_MAX_CHARS) + "…";
    }
}
