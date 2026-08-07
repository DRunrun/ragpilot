# Architecture

## 一句话

RagPilot = **可评测、可观测的 Java RAG 运行时**（先 RAG，后轻量 Agent）。  
Spring AI 负责模型调用抽象；本项目负责 ingestion / 检索编排 / citation / eval / trace。

## 模块图

```
┌─────────────────────────────────────────────────────────┐
│                 ragpilot-bootstrap (Spring Boot)         │
│   REST SSE / CLI  ·  组装 ChatClient · 读取配置          │
└─────────────┬───────────────────────────┬───────────────┘
              │                           │
              ▼                           ▼
┌─────────────────────┐     ┌─────────────────────────────┐
│   ragpilot-ops      │     │      ragpilot-eval          │
│ Trace / Token /     │     │ GoldenSet / Metrics / Report│
│ PromptVersion       │     └──────────────▲──────────────┘
└──────────┬──────────┘                    │
           │                               │
           └──────────────┬────────────────┘
                          ▼
           ┌──────────────────────────────┐
           │       ragpilot-core          │
           │ ingestion · retrieval ·      │
           │ generation · (agent M3)      │
           │ **无 Spring 依赖**            │
           └──────────────────────────────┘
```

## 包结构（目标）

```
com.ragpilot.core
  ├── domain          # Document, TextChunk, Citation, AskResult...
  ├── ingestion       # DocumentParser, Chunker, IngestionPipeline
  ├── retrieval       # Embedder, VectorStore, HybridRetriever, Reranker
  ├── generation      # PromptBuilder, Generator, CitationPolicy
  └── agent           # ReActAgent（M3，勿提前塞满）

com.ragpilot.eval
  ├── dataset
  ├── metrics
  ├── judge
  └── report

com.ragpilot.ops
  ├── trace
  ├── token
  └── prompt

com.ragpilot.bootstrap
  ├── web             # SSE controller
  ├── cli             # 可选
  └── config          # RagPilotProperties, beans
```

## 核心领域对象（约定）

| 类型 | 含义 |
|---|---|
| `SourceDocument` | 原始文档（id, uri, mime, rawText, metadata） |
| `TextChunk` | 分块（id, docId, content, index, metadata） |
| `RetrievedChunk` | 检索命中（chunk, score, rank, channel: VECTOR\|BM25\|RRF） |
| `Citation` | 引用（index, chunkId, docId, snippet） |
| `AskRequest` | 用户问题 + 可选 filters |
| `AskResult` | answer, citations, refused, traceId, tokenUsage |

## 依赖规则

见根目录 [`AGENTS.md`](../AGENTS.md)。违反依赖方向的 PR/改动一律打回。

## 运行时配置默认值

见 [`m1-spec.md`](m1-spec.md)「默认配置表」。改默认值必须更新该表与单测。
