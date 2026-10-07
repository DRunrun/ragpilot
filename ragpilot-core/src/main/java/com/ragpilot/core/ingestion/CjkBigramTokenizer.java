package com.ragpilot.core.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 中英混排分词器：把文本切成「PG simple 配置可用的小写无关 token 序列」——
 * 拉丁词原样保留，中文按 <b>bigram（相邻两字滑窗）</b> 切分。
 *
 * <p>链路位置：跨摄入与关键词检索两端的纯函数。摄入端把结果写进
 * {@code metadata.searchTokens} 供 {@code content_tsv} 生成列使用；
 * 检索端把查询切成同样的 token 拼成 tsquery——两侧口径必须一致，否则召回为空。
 *
 * <p>为什么要它：Postgres 原生 {@code simple} 配置不认中文，整句汉字会糊成
 * 一个巨型 token，{@code plainto_tsquery} 对中文问句几乎永远不命中（F2.1 已知短板）。
 * 上 zhparser 扩展要换镜像、加依赖，都违反 AGENTS.md 技术选型锁定；
 * bigram 是「纯 PG 内解决」的最小可行方案：中文「生命周期」切成
 * 生命/命周/周期，与同样切成 bigram 的入库文本直接对上。
 *
 * <p>已知取舍：bigram 会有「的是」这类噪声词，靠 {@code ts_rank} 排序压制；
 * OR 语义换召回最大化（旧 plainto 是 AND，中文场景基本查不出东西）。
 */
public final class CjkBigramTokenizer {

    /** 单个查询最多 token 数：防止超长问句把 tsquery 拼爆（PG tsquery 有长度上限）。 */
    public static final int MAX_QUERY_TOKENS = 64;

    private CjkBigramTokenizer() {
    }

    /** 是否 CJK 基本汉字/扩展 A 区（本项目语料覆盖范围，生僻区不做）。 */
    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF');
    }

    /** 拉丁类 token 字符：字母数字（CJK 除外，已在前一分支截断成词）。 */
    private static boolean isLatinPart(char c) {
        return Character.isLetterOrDigit(c) && !isCjk(c);
    }

    /**
     * 切 token：连续拉丁字符为一个 token；连续汉字产出相邻两字 bigram。
     *
     * <p>汉字单字 run（长度 1，如「语」单独出现）产出单字 token，保证不丢词。
     * 不做大小写折叠——'simple' 配置本就不折叠，索引侧与查询侧保持一致才匹配得上。
     *
     * @param text 任意文本；null/空白返回空列表
     * @return 去重且保序的 token 列表
     */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                int j = i;
                while (j < n && isCjk(text.charAt(j))) {
                    j++;
                }
                String run = text.substring(i, j);
                if (run.length() == 1) {
                    out.add(run);
                } else {
                    for (int k = 0; k + 2 <= run.length(); k++) {
                        out.add(run.substring(k, k + 2));
                    }
                }
                i = j;
            } else if (isLatinPart(c)) {
                int j = i;
                while (j < n && isLatinPart(text.charAt(j))) {
                    j++;
                }
                out.add(text.substring(i, j));
                i = j;
            } else {
                // 标点/空白：天然分隔符
                i++;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 摄入侧：产出写入 {@code metadata.searchTokens} 的索引文本。
     *
     * <p>只在文本<b>含中文</b>时产出：纯英文文档返回 null，让生成列走
     * 原有 regexp_replace 兜底表达式，英文召回路径行为零变化（回归安全）。
     *
     * @param text 分块原文
     * @return 空格分隔的 token 串；不含中文或无有效 token 时返回 null
     */
    public static String tokenizeForIndex(String text) {
        if (text == null || text.isBlank() || !containsCjk(text)) {
            return null;
        }
        List<String> tokens = tokens(text);
        return tokens.isEmpty() ? null : String.join(" ", tokens);
    }

    /**
     * 检索侧：把查询拼成 {@code to_tsquery('simple', ...)} 的表达式。
     *
     * <p>token 间用 {@code |}（OR）：中文问句的 bigram 全 AND 几乎不可能同时出现，
     * OR 靠 ts_rank 让命中多的块排前面；英文侧 OR 同样只增召回不改排序语义。
     * token 由构造保证只含字母数字，直接加引号无需转义。
     *
     * @param query 用户问题/关键词
     * @return 形如 {@code '生命' | '命周' | 'Bean'} 的 tsquery；无 token 返回 null
     */
    public static String toTsQuery(String query) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(tokens.size(), MAX_QUERY_TOKENS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append('\'').append(tokens.get(i)).append('\'');
        }
        return sb.toString();
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
