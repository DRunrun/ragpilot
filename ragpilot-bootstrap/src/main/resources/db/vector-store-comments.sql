-- RagPilot：为 Spring AI 自动创建的 vector_store 表补充中文注释。
-- 表结构本身由 spring.ai.vectorstore.pgvector.initialize-schema 维护，本脚本只写 COMMENT，可重复执行。

COMMENT ON TABLE vector_store IS
    'RAG 向量存储表：保存文档分块文本及其 embedding，供相似度检索（ingestion 写入 / ask 检索）';

COMMENT ON COLUMN vector_store.id IS
    '行主键（UUID）。业务侧分块 ID（如 spring-bean-lifecycle#0）存在 metadata.chunkId，不直接用业务 ID 当主键';

COMMENT ON COLUMN vector_store.content IS
    '分块文本。nomic-embed 入库时带任务前缀 search_document:；给 LLM 看的原文在 metadata.rawContent';

COMMENT ON COLUMN vector_store.metadata IS
    'JSON 元数据。常用键：docId（文档 ID）、chunkId（业务分块 ID）、rawContent（无前缀原文）、title（标题）';

COMMENT ON COLUMN vector_store.embedding IS
    '文本向量，维度 768，对应 embedding 模型 text-embedding-nomic-embed-text-v1.5；索引为 HNSW + cosine';
