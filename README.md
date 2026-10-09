# RagPilot

Evaluation-driven Java RAG runtime on Spring AI — measurable retrieval, citation-grounded answers, private-by-default Quickstart.

> **Engineering source of truth:** [`AGENTS.md`](AGENTS.md) + [`docs/`](docs/)  
> Product / interview notes live in Obsidian (`02-开源项目-RagPilot`); if they conflict, **this repo wins**.

## Ablation (M2 · golden v1.0 · 50 questions)

| setup | Recall@5 | Faithfulness | AnswerRelevancy | notes |
|---|---:|---:|---:|---|
| VECTOR | 0.917 | n/a* | n/a* | baseline vector-only |
| HYBRID | 0.917 | n/a* | n/a* | BM25 + vector + RRF |
| HYBRID_RERANK | 0.917 | n/a* | n/a* | hybrid + rerank (NoOp when disabled) |

**结论（当前单篇样例语料）：** 三模式 Recall@5 持平（0.917，相对 VECTOR **+0.0%**）。混合检索的增益需要更广的多文档语料与开启 `--judge` / 真实 BGE Rerank 后才会拉开——完整表与复跑命令见 [`reports/ablation-2026-08-08.md`](reports/ablation-2026-08-08.md)。

\* Faithfulness / AnswerRelevancy：加 `--judge` 跑全量 LLM-as-Judge 后回填（`temperature=0`）。

```bash
# 一键复跑消融（检索指标）
java -jar ragpilot-bootstrap/target/ragpilot-bootstrap-0.1.0-SNAPSHOT.jar eval --mode=VECTOR --out=reports/eval-VECTOR.json
java -jar ragpilot-bootstrap/target/ragpilot-bootstrap-0.1.0-SNAPSHOT.jar eval --mode=HYBRID --out=reports/eval-HYBRID.json
java -jar ragpilot-bootstrap/target/ragpilot-bootstrap-0.1.0-SNAPSHOT.jar eval --mode=HYBRID_RERANK --out=reports/eval-HYBRID_RERANK.json
java -jar ragpilot-bootstrap/target/ragpilot-bootstrap-0.1.0-SNAPSHOT.jar report \
  --inputs=reports/eval-VECTOR.json,reports/eval-HYBRID.json,reports/eval-HYBRID_RERANK.json \
  --out=reports/ablation-$(date +%F).md
```

## Status (M2)

- [x] Repo + module skeleton + coding docs  
- [x] Bootstrap Hello World via **LM Studio** (OpenAI-compatible)  
- [x] M1 RAG pipeline: ingestion → 向量检索 → 流式生成 + citation + 拒答 — see [`docs/m1-spec.md`](docs/m1-spec.md)  
- [x] M2 retrieval: BM25 / RRF / Rerank pluggable + `VECTOR|HYBRID|HYBRID_RERANK`  
- [x] M2 eval: golden v1.0 (50) + Recall@k / MRR + LLM-as-Judge + ablation report  
- [x] M3 Agent: ReAct + tools (knowledge_search / http_get / optional mcp_proxy) + Trace UI  

## Modules

| Module | Role |
|---|---|
| `ragpilot-core` | Domain + RAG logic, **no Spring** |
| `ragpilot-eval` | Golden set, metrics, reports |
| `ragpilot-ops` | Trace, token, prompt version |
| `ragpilot-bootstrap` | Spring Boot app (SSE / wiring / eval CLI) |

## Quickstart — RAG 问答（完整步骤见 [`docs/quickstart.md`](docs/quickstart.md)）

Prerequisites: JDK 21、Docker（PGVector）、[LM Studio](https://lmstudio.ai)（chat + embedding 模型已加载并开启 Server）。

可用环境变量覆盖：`RAGPILOT_LLM_BASE_URL`、`RAGPILOT_CHAT_MODEL`、`RAGPILOT_EMBEDDING_MODEL`、`RAGPILOT_DB_HOST/PORT/USER/PASSWORD`。数据库口令**不要**写进仓库，从 `.env` 注入。

```bash
# 0) 本地密钥（口令留空会启动失败）
cp .env.example .env
# 编辑 .env：POSTGRES_PASSWORD 与 RAGPILOT_DB_PASSWORD 填同一组本地口令

# 1) 起向量库（PG + pgvector，宿主机 5433）
docker compose up -d

# 2) 启动应用（把 .env 导出到当前 shell，否则 Spring 解析不到 RAGPILOT_DB_PASSWORD）
set -a && source .env && set +a
./mvnw -pl ragpilot-bootstrap -am spring-boot:run

# 3) 灌入样例语料
curl -X POST localhost:8081/api/v1/ingest/samples

# 4) 流式提问（SSE：citation → token → done，无命中时 refused）
curl -N localhost:8081/api/v1/ask \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"question":"Spring Bean 的生命周期是什么？"}'

# 5) 或直接打开最小聊天 UI
open http://127.0.0.1:8081/
```

网关自检（不依赖 PG）：

```bash
curl -s http://127.0.0.1:8081/api/v1/gateway
```

## Docs

| Doc | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Dev constraints for humans & AI |
| [docs/execution-plan.md](docs/execution-plan.md) | **Global feature checklist & schedule** |
| [docs/architecture.md](docs/architecture.md) | Module boundaries |
| [docs/quickstart.md](docs/quickstart.md) | Run it from zero |
| [docs/m1-spec.md](docs/m1-spec.md) | M1 executable spec |
| [docs/evaluation.md](docs/evaluation.md) | Eval schema & metrics |
| [docs/mcp.md](docs/mcp.md) | Optional MCP client demo (F3.7) |
| [docs/coding-standards.md](docs/coding-standards.md) | Java conventions |
| [docs/ui-strategy.md](docs/ui-strategy.md) | Minimal UI policy |

## License

Apache-2.0
