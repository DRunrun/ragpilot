package com.ragpilot.core.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CjkBigramTokenizer} 单测：中文 bigram / 拉丁 token / 两侧口径一致性。
 */
class CjkBigramTokenizerTest {

    @Test
    @DisplayName("中文连续串切相邻两字 bigram")
    void cjkRunProducesBigrams() {
        assertEquals(List.of("生命", "命周", "周期"),
                CjkBigramTokenizer.tokens("生命周期"));
    }

    @Test
    @DisplayName("中英混排：拉丁词保留，标点做分隔符")
    void mixedTextKeepsLatinTokens() {
        List<String> tokens = CjkBigramTokenizer.tokens("Bean 生命周期，afterInitialize 方法");
        assertTrue(tokens.contains("Bean"), "拉丁词应原样保留");
        assertTrue(tokens.contains("afterInitialize"));
        assertTrue(tokens.contains("生命"));
        assertTrue(tokens.contains("命周"));
        assertTrue(tokens.contains("周期"));
        // 标点不应出现在任何 token 里
        assertTrue(tokens.stream().noneMatch(t -> t.contains("，")));
    }

    @Test
    @DisplayName("单个汉字成独立 token，不丢词")
    void singleCjkCharYieldsItself() {
        assertEquals(List.of("语"), CjkBigramTokenizer.tokens("语"));
    }

    @Test
    @DisplayName("摄入侧：纯英文返回 null，让生成列走原表达式（英文零回归）")
    void indexSkipsPureLatinText() {
        assertNull(CjkBigramTokenizer.tokenizeForIndex("Bean lifecycle callbacks"));
        assertNull(CjkBigramTokenizer.tokenizeForIndex(null));
        assertNull(CjkBigramTokenizer.tokenizeForIndex("   "));
    }

    @Test
    @DisplayName("摄入侧：含中文才产出分词串")
    void indexProducesJoinedTokensForCjk() {
        String indexed = CjkBigramTokenizer.tokenizeForIndex("Bean生命周期");
        assertEquals("Bean 生命 命周 周期", indexed);
    }

    @Test
    @DisplayName("查询侧：token 用 | 连接（OR），引号包裹")
    void tsQueryUsesOrSemantics() {
        assertEquals("'生命' | '命周' | '周期'",
                CjkBigramTokenizer.toTsQuery("生命周期"));
        // 去重：重复 bigram 只出现一次（“生命 生命命” → 生命/命命）
        assertEquals("'生命' | '命命'", CjkBigramTokenizer.toTsQuery("生命 生命命"));
        // 切不出 token → null（调用方按无结果处理）
        assertNull(CjkBigramTokenizer.toTsQuery("！！！"));
        assertNull(CjkBigramTokenizer.toTsQuery(null));
    }

    @Test
    @DisplayName("查询 token 数封顶，防超长 tsquery 撞 PG 上限")
    void tsQueryCapped() {
        // 70 个互不相同的汉字 → 69 个不重复 bigram，应被截到 MAX_QUERY_TOKENS
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append((char) ('一' + i));
        }
        String tsQuery = CjkBigramTokenizer.toTsQuery(sb.toString());
        int count = tsQuery.split(" \\| ").length;
        assertEquals(CjkBigramTokenizer.MAX_QUERY_TOKENS, count);
    }

    @Test
    @DisplayName("索引侧与查询侧同口径：问句 bigram 能命中入库分词")
    void indexAndQueryShareTokenization() {
        String doc = "容器的生命周期由 BeanPostProcessor 参与";
        String query = "生命周期";
        String indexed = CjkBigramTokenizer.tokenizeForIndex(doc);
        String tsQuery = CjkBigramTokenizer.toTsQuery(query);
        // 查询的每个 token 都应存在于索引 token 集合中（模拟 @@ 匹配的前提）
        List<String> indexedTokens = List.of(indexed.split(" "));
        for (String tokenPart : tsQuery.split(" \\| ")) {
            String token = tokenPart.replace("'", "");
            assertTrue(indexedTokens.contains(token), "索引应含查询 token: " + token);
        }
    }
}
