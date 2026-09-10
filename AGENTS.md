# RagPilot — Agent / 开发约束

> 本文件是**写代码时的权威约束**。战略与面试话术见 Obsidian `面试/02-开源项目-RagPilot.md`。  
> 两者冲突时：**以本仓库 `AGENTS.md` + `docs/` 为准。**

## 项目目标（8 周）

交付四样东西，多的不做：

1. 可运行 RAG（ingestion → 检索 → 流式生成 + citation）
2. 一份消融评测报告（至少 2 组对比）
3. 可 live demo（**最小聊天 UI** + SSE API）
4. （可选）CLI / curl 自检脚本

## 模块边界（强制）

| 模块 | 允许 | 禁止 |
|---|---|---|
| `ragpilot-core` | 纯 Java 领域模型、分块/检索/生成接口与实现（可依赖向量库驱动，但**无 Spring**） | 引入 `spring-*`、Web、Boot |
| `ragpilot-eval` | 黄金集、指标、Judge、报告生成 | 依赖 Web 层；把业务 RAG 逻辑写在这里 |
| `ragpilot-ops` | Trace、Token 计量、Prompt 版本 | 实现检索/分块算法 |
| `ragpilot-bootstrap` | Spring Boot 组装、HTTP/CLI、配置、静态 Demo、**`/api/admin/v1`** | 堆领域算法；实现用户/RBAC |
| `ragpilot-admin-ui` | Vue3 + Element Plus 管理端（仓库内前端模块） | 实现检索/分块/评测算法；提交 `node_modules` |
| `ragpilot-starter`（P1） | 自动配置、对外 Starter | 复制 core 逻辑 |

**依赖方向（单向）**：

```
admin-ui（浏览器）→ bootstrap / starter → ops → eval → core
                              ↘________↗
不允许 core → eval/ops/bootstrap/admin-ui
不允许 admin-ui 直接依赖 Java 模块源码
```

## 技术选型（锁定）

- Java **21**、Maven、Spring Boot **3.4+**、Spring AI（OpenAI 兼容客户端）
- 本地网关默认 **LM Studio**（`http://127.0.0.1:1234`）；也可换 Ollama/云端（改 base-url + model）
- 向量库默认 **PGVector**；Embedding 后续接 LM Studio 中的 embedding 模型（你已有 `text-embedding-nomic-embed-text-v1.5`）
- Demo 语料：**Spring 官方文档**（公开、合法）
- **不引入** LangChain4j / LangGraph 作为主依赖（概念映射仅文档）
- **允许** bootstrap 内嵌**最小聊天 UI**（静态页；管理端上线后逐步被 `ragpilot-admin-ui` 在线调试替代）
- **允许** 仓库内管理端 `ragpilot-admin-ui`（Vue3 + Element Plus）+ bootstrap `/api/admin/v1`：配置 DB 覆盖、**知识库**/分块/向量库运维/Prompt/评测等（见 [`docs/admin-console-spec.md`](docs/admin-console-spec.md)）
- **不做** 用户/权限/RBAC、多租户、NL2SQL；**不做**公网无防护暴露 Admin（Defer/禁止）
- 管理端 **暂缓开发** 直至计划中的开工口令；规范优先于写码

## 编码规范（摘要）

完整版见 [`docs/coding-standards.md`](docs/coding-standards.md)。

- **注释一律用中文**：类/接口必须有 Javadoc 说明「是什么 + 在 RAG 链路哪一环 + 为什么」；公有方法说明参数与边界；关键算法与踩坑写行内注释。详见 `docs/coding-standards.md` 第〇节。
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
| [`docs/execution-plan.md`](docs/execution-plan.md) | **全局功能清单与执行计划（按此推进）** |
| [`docs/architecture.md`](docs/architecture.md) | 架构与包结构 |
| [`docs/m1-spec.md`](docs/m1-spec.md) | M1 可执行规格 |
| [`docs/evaluation.md`](docs/evaluation.md) | 评测数据与指标 |
| [`docs/coding-standards.md`](docs/coding-standards.md) | Java 规范细节 |
| [`docs/ui-strategy.md`](docs/ui-strategy.md) | UI 演进：Demo → Admin |
| [`docs/admin-console-plan.md`](docs/admin-console-plan.md) | 管理端里程碑与 ADM 任务清单 |
| [`docs/admin-console-spec.md`](docs/admin-console-spec.md) | 管理端边界、API/DB/前端规范 |
| Obsidian `02-开源项目-RagPilot` | 产品定位与面试映射 |
