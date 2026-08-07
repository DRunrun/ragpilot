# M1 可执行规格（Week 1～2）

> 完成定义：陌生人（或面试官）用文档里的命令能跑通「问一句 Spring 相关问题，得到带引用或明确拒答的流式回答」。  
> M1 **不要求**：混合检索、Rerank、Agent、MCP、完整评测自动化（这些属 M2/M3）。

## 1. 范围

**做：**

- 文档解析（至少 Markdown + HTML/纯文本；Tika 可随后加）
- 固定大小分块（默认见配置表）
- Embedding 写入 PGVector
- 向量 Top-K 检索
- Prompt 组装 + 流式生成
- Citation（答案中标注来源）+ 无命中拒答
- CLI 或 HTTP SSE 二选一即可（推荐先 HTTP SSE）

**不做：** BM25、RRF、Rerank、ReAct、Console。

## 2. 默认配置表

| 键 | 默认值 | 说明 |
|---|---|---|
| `ragpilot.chunk.size-tokens` | `512` | 近似按字符/ token 策略在实现中注明 |
| `ragpilot.chunk.overlap-tokens` | `64` | |
| `ragpilot.retrieval.top-k` | `5` | 向量召回 |
| `ragpilot.retrieval.min-score` | `0.0` | 先放宽，M2 再调 |
| `ragpilot.generation.temperature` | `0.0` | 评测可复现 |
| `ragpilot.generation.model` | `qwen3:32b` | Ollama 模型名可配置 |
| `ragpilot.embedding.model` | `bge-m3` | |
| `ragpilot.refusal.empty-hits` | `true` | 无检索命中则拒答 |

实现时映射到 `RagPilotProperties`，禁止硬编码散落。

## 3. API 契约

### 3.1 HTTP（推荐）

`POST /api/v1/ask`  
`Accept: text/event-stream`（流式）或 `application/json`（非流式调试）

**Request**

```json
{
  "question": "Spring Bean 的生命周期是什么？",
  "topK": 5
}
```

**SSE 事件（建议）**

- `event: token` — `data: {"text":"..."}`  
- `event: citation` — `data: {"index":1,"docId":"...","chunkId":"...","snippet":"..."}`  
- `event: done` — `data: {"refused":false,"traceId":"...","promptTokens":0,"completionTokens":0}`  
- `event: refused` — `data: {"reason":"NO_EVIDENCE","message":"根据现有资料无法回答"}`

**JSON 非流式响应**

```json
{
  "answer": "……[1]……",
  "refused": false,
  "citations": [
    {
      "index": 1,
      "docId": "spring-beans",
      "chunkId": "spring-beans#12",
      "snippet": "……"
    }
  ],
  "traceId": "01J...",
  "tokenUsage": { "prompt": 1200, "completion": 300 }
}
```

### 3.2 CLI（可选等价）

```bash
./mvnw -pl ragpilot-bootstrap -am spring-boot:run
# 或
java -jar ... ask "Spring Bean 的生命周期是什么？"
```

## 4. Citation / 拒答规则

1. Prompt 必须包含：「仅基于下列上下文回答；无足够证据时明确说无法回答；引用用 `[n]`。」  
2. 上下文块格式：`[n] source=... chunkId=...\n{content}`  
3. `hits.isEmpty()` → **不得调用「自由发挥」生成**；直接 `refused=true`  
4. 生成后尽量校验：答案中的 `[n]` 是否都在 citations 列表中（M1 可做软校验 + 日志）

## 5. 目录与类（M1 最小集）

```
ragpilot-core/
  .../domain/SourceDocument.java, TextChunk.java, Citation.java, AskResult.java
  .../ingestion/FixedSizeChunker.java
  .../retrieval/Retrieving.java (interface), ...
  .../generation/PromptBuilder.java

ragpilot-bootstrap/
  .../config/RagPilotProperties.java
  .../web/AskController.java
  .../RagpilotBootstrapApplication.java
```

M1 允许：向量写入/检索先用 **Spring AI VectorStore** 适配器放在 bootstrap 或 core 的 `spi` 包，但**领域类型必须在 core**。若暂时为赶进度把适配器放 bootstrap，M2 前下沉，并在此文档备注。

## 6. 完成定义（DoD）—— 全部勾上才算 M1 完

- [ ] `./mvnw -q test` 通过（纯单测，不依赖 Ollama）
- [ ] 本地 Ollama 已拉生成模型 + embedding 模型
- [ ] PG 含 PGVector，Compose 或本地可连
- [ ] 能 ingest 至少 1 份 Spring 文档样例（放 `samples/`）
- [ ] `POST /api/v1/ask` 对流式问题返回 token + 至少 1 个 citation **或** 合法 refused
- [ ] README Quickstart ≤ 10 步能复现
- [ ] 无「无命中仍长篇胡答」的路径

## 7. 验收命令（模板）

```bash
# 1) 起依赖
docker compose up -d

# 2) 启动应用
./mvnw -pl ragpilot-bootstrap -am spring-boot:run

# 3) 摄入（M1 可用临时 endpoint 或 CommandLineRunner）
curl -X POST localhost:8080/api/v1/ingest -H 'Content-Type: application/json' \
  -d '{"path":"samples/spring-bean-lifecycle.md"}'

# 4) 提问
curl -N localhost:8080/api/v1/ask \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"question":"Spring Bean 的生命周期是什么？"}'
```

（具体路径以实现为准，实现后回写本节为真实命令。）

## 8. Week 0 Hello World（本仓库当前阶段）

当前仅要求：

- 多模块可编译
- `ragpilot-bootstrap` 能连 Ollama 做一次**纯聊天流式**（可不经 RAG）
- 证明 Spring AI + Ollama 通路正常

RAG 管线从 Week 1 按本文规格推进。
