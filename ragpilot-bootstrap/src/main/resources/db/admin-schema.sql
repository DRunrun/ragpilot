-- RagPilot Admin 元数据表（ADM-0.5）
-- 语句分隔：独占一行的 -- ###STMT###

CREATE TABLE IF NOT EXISTS ragpilot_runtime_config (
    config_key   VARCHAR(256) PRIMARY KEY,
    config_value TEXT         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(64)  NULL
);

-- ###STMT###

CREATE TABLE IF NOT EXISTS knowledge_base (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    description TEXT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ###STMT###

CREATE TABLE IF NOT EXISTS knowledge_document (
    id                 VARCHAR(64) PRIMARY KEY,
    knowledge_base_id  VARCHAR(64) NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id             VARCHAR(256) NOT NULL,
    title              VARCHAR(512) NULL,
    source_uri         TEXT NULL,
    content_text       TEXT NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    chunk_count        INT NOT NULL DEFAULT 0,
    error_message      TEXT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (knowledge_base_id, doc_id)
);

-- ###STMT###

CREATE INDEX IF NOT EXISTS idx_knowledge_document_kb
    ON knowledge_document (knowledge_base_id);

-- ###STMT###

-- 兼容已建表环境：补 content_text（重灌用）
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content_text TEXT;

-- ###STMT###

INSERT INTO knowledge_base (id, name, description, enabled)
VALUES ('default', '默认知识库', '兼容历史无库摄入；samples 与旧向量默认归属此库', TRUE)
ON CONFLICT (id) DO NOTHING;

-- ###STMT###

CREATE TABLE IF NOT EXISTS prompt_template (
    version     VARCHAR(64) PRIMARY KEY,
    content     TEXT NOT NULL,
    source      VARCHAR(32) NOT NULL DEFAULT 'custom',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ###STMT###

CREATE TABLE IF NOT EXISTS eval_job (
    id              VARCHAR(64) PRIMARY KEY,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    mode            VARCHAR(32) NOT NULL,
    golden_set      VARCHAR(64) NOT NULL,
    top_k           INT NOT NULL DEFAULT 5,
    with_judge      BOOLEAN NOT NULL DEFAULT FALSE,
    progress        INT NOT NULL DEFAULT 0,
    result_path     TEXT NULL,
    report_markdown TEXT NULL,
    error_message   TEXT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMPTZ NULL
);

-- ###STMT###

CREATE TABLE IF NOT EXISTS chat_session (
    id          VARCHAR(64) PRIMARY KEY,
    title       VARCHAR(256) NULL,
    mode        VARCHAR(32) NOT NULL DEFAULT 'RAG',
    knowledge_base_id VARCHAR(64) NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ###STMT###

CREATE TABLE IF NOT EXISTS chat_message (
    id              VARCHAR(64) PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role            VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    citations_json  TEXT NULL,
    trace_id        VARCHAR(64) NULL,
    prompt_version  VARCHAR(64) NULL,
    token_count     INT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ###STMT###

CREATE INDEX IF NOT EXISTS idx_chat_message_session
    ON chat_message (session_id, created_at);
