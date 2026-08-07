# RagPilot — Agent / 开发约束

> 本文件是**写代码时的权威约束**。战略与面试话术见 Obsidian `面试/02-开源项目-RagPilot.md`。  
> 两者冲突时：**以本仓库 `AGENTS.md` + `docs/` 为准。**

## 项目目标（8 周）

交付三样东西，多的不做：

1. 可运行 RAG（ingestion → 检索 → 流式生成 + citation）
2. 一份消融评测报告（至少 2 组对比）
3. 可 live demo（CLI 或 SSE）

## 模块边界（强制）

| 模块 | 允许 | 禁止 |
|---|---|---|
| `ragpilot-core` | 纯 Java 领域模型、分块/检索/生成接口与实现（可依赖向量库驱动，但**无 Spring**） | 引入 `spring-*`、Web、Boot |
| `ragpilot-eval` | 黄金集、指标、Judge、报告生成 | 依赖 Web 层；把业务 RAG 逻辑写在这里 |
| `ragpilot-ops` | Trace、Token 计量、Prompt 版本 | 实现检索/分块算法 |
| `ragpilot-bootstrap` | Spring Boot 组装、HTTP/CLI、配置 | 堆领域逻辑（应下沉到 core） |
| `ragpilot-starter`（P1） | 自动配置、对外 Starter | 复制 core 逻辑 |

**依赖方向（单向）**：

```
bootstrap / starter → ops → eval → core
                 ↘________↗
不允许 core → eval/ops/bootstrap
```

## 技术选型（锁定）

- Java **21**、Maven、Spring Boot **3.4+**、Spring AI（Ollama / OpenAI 兼容）
- 向量库默认 **PGVector**；Embedding **bge-m3**；生成默认本地 Ollama
- Demo 语料：**Spring 官方文档**（公开、合法）
- **不引入** LangChain4j / LangGraph 作为主依赖（概念映射仅文档）
- **不做** console UI、NL2SQL（Defer）

## 编码规范（摘要）

完整版见 [`docs/coding-standards.md`](docs/coding-standards.md)。

- 包名：`com.ragpilot.*`
- 公开 API 用 `record` / 不可变对象；避免裸 `Map<String,Object>` 作为契约
- 检索与生成路径必须能产出 **citation**；无证据时 **拒答**，禁止瞎编
- 配置项集中在 `application.yml` + `RagPilotProperties`，禁止魔法数散落
- 每个可测试的纯逻辑放 core，**单测优先**；涉及 LLM 的集成测试打 `@Tag("integration")`，默认 CI 可跳过
- 提交信息：英文或中文均可，说明 **why**；一次提交一件事

## 变更流程

1. 改行为前先看 [`docs/m1-spec.md`](docs/m1-spec.md) / 对应里程碑规格是否需要更新  
2. 改 Chunk / Embedding / Prompt / 检索策略 → 必须能跑评测（M2 起强制）  
3. 新增依赖：写进父 POM 管理；说明理由（PR 或 commit body）  
4. 禁止 `System.out` 打生产日志；用 SLF4J  

## AI 辅助开发额外约束

- 不要为了「完整」实现 Defer 清单  
- 不要生成无调用方的巨型工具类  
- 新增文件前确认落入正确模块  
- 接口变更同步更新 `docs/m1-spec.md` 中的 JSON 示例  

## 文档索引

| 文档 | 用途 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | 架构与包结构 |
| [`docs/m1-spec.md`](docs/m1-spec.md) | M1 可执行规格 |
| [`docs/evaluation.md`](docs/evaluation.md) | 评测数据与指标 |
| [`docs/coding-standards.md`](docs/coding-standards.md) | Java 规范细节 |
| Obsidian `02-开源项目-RagPilot` | 产品定位与面试映射 |
