-- RagPilot F2.1：为 vector_store 补全文检索列与 GIN 索引（BM25 近似）。
-- 优先用 metadata.searchTokens（含中文块的 bigram 分词，摄入端 CjkBigramTokenizer 产出）；
-- 无分词的存量/纯英文块回退原表达式：非字母数字替换为空格再分词，
-- 这样 BeanPostProcessor.beforeInitialize 会拆成可独立匹配的 token。
-- 配置 simple：不引入中文分词扩展。启动器可重复执行（先删后建生成列）。

DROP INDEX IF EXISTS vector_store_content_tsv_gin
-- ###STMT###
ALTER TABLE vector_store DROP COLUMN IF EXISTS content_tsv
-- ###STMT###
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (
            to_tsvector(
                'simple',
                coalesce(
                    metadata->>'searchTokens',
                    regexp_replace(
                        coalesce(metadata->>'rawContent', content),
                        '[^[:alnum:]]+',
                        ' ',
                        'g'
                    )
                )
            )
        ) STORED
-- ###STMT###
CREATE INDEX IF NOT EXISTS vector_store_content_tsv_gin
    ON vector_store USING GIN (content_tsv)
-- ###STMT###
COMMENT ON COLUMN vector_store.content_tsv IS
    '全文检索向量（simple）。优先 metadata.searchTokens（中文 bigram 分词）；否则非字母数字替换为空格再分词（F2.1 + 中文召回优化）'
