-- RagPilot F2.1：为 vector_store 补全文检索列与 GIN 索引（BM25 近似）。
-- 优先用 metadata.rawContent；把非字母数字替换为空格再分词，
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
                regexp_replace(
                    coalesce(metadata->>'rawContent', content),
                    '[^[:alnum:]]+',
                    ' ',
                    'g'
                )
            )
        ) STORED
-- ###STMT###
CREATE INDEX IF NOT EXISTS vector_store_content_tsv_gin
    ON vector_store USING GIN (content_tsv)
-- ###STMT###
COMMENT ON COLUMN vector_store.content_tsv IS
    '全文检索向量（simple）。非字母数字已替换为空格再分词，便于类名.方法名精确召回（F2.1）'
