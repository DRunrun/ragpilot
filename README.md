# RagPilot

Evaluation-driven Java RAG runtime on Spring AI — measurable retrieval, citation-grounded answers, private-by-default Quickstart.

> **Engineering source of truth:** [`AGENTS.md`](AGENTS.md) + [`docs/`](docs/)  
> Product / interview notes live in Obsidian (`02-开源项目-RagPilot`); if they conflict, **this repo wins**.

## Status (Week 0)

- [x] Repo + module skeleton + coding docs  
- [x] Bootstrap Hello World (Ollama chat stream)  
- [ ] M1 RAG pipeline — see [`docs/m1-spec.md`](docs/m1-spec.md)

## Modules

| Module | Role |
|---|---|
| `ragpilot-core` | Domain + RAG logic, **no Spring** |
| `ragpilot-eval` | Golden set, metrics, reports |
| `ragpilot-ops` | Trace, token, prompt version |
| `ragpilot-bootstrap` | Spring Boot app (SSE / wiring) |

## Quickstart — Hello World (chat only)

Prerequisites: JDK 21, [Ollama](https://ollama.com), model e.g. `ollama pull qwen3:32b` (or set `ragpilot.hello.model`).

```bash
./mvnw -pl ragpilot-bootstrap -am spring-boot:run
curl -N "http://localhost:8080/api/v1/hello?q=用一句话解释什么是RAG"
```

## Docs

| Doc | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Dev constraints for humans & AI |
| [docs/architecture.md](docs/architecture.md) | Module boundaries |
| [docs/m1-spec.md](docs/m1-spec.md) | M1 executable spec |
| [docs/evaluation.md](docs/evaluation.md) | Eval schema & metrics |
| [docs/coding-standards.md](docs/coding-standards.md) | Java conventions |

## License

Apache-2.0
