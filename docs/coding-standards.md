# Coding Standards

## 语言与构建

- Java 21；`./mvnw` 包装器提交进库  
- 缩进 4 空格；UTF-8；禁止提交 IDE 私有配置（可提交 `.idea` 运行配置需谨慎）  
- 公有 API Javadoc 一句话说明职责；内部实现不强制长文档  

## 命名

| 类型 | 规则 | 例 |
|---|---|---|
| 包 | `com.ragpilot.<module>...` | `com.ragpilot.core.retrieval` |
| 接口 | 名词/形容词能力 | `Chunker`, `HybridRetriever` |
| 实现 | 接口名 + 策略 | `FixedSizeChunker`, `RrfHybridRetriever` |
| 配置属性 | `ragpilot.*` | `ragpilot.chunk.size-tokens` |

## 设计

- 优先 `record` 做 DTO/领域事件  
- 接口隔离：core 定义端口，基础设施实现可放 bootstrap 适配器（标注 `@Deprecated` 迁移计划若暂放错层）  
- 异常：业务可恢复用受检或明确 result 类型（如 `AskResult.refused`）；基础设施失败用运行时异常 + 错误码  
- **禁止**在 core 使用 `@Autowired` / Spring 注解  

## 测试

- core：纯单元测试，无网络、无 DB  
- bootstrap：`@SpringBootTest` + Testcontainers（PG）可选；默认不强制  
- 凡调用真实 LLM：`@Tag("integration")`，并用 Surefire 排除标签（父 POM 配置）  

## 日志与可观测

- SLF4J；敏感内容（完整 Prompt）默认 debug，且注意脱敏  
- 每个 ask 请求分配 `traceId`（M1 可用 UUID）  

## Git

- 不提交：`.env`、API Key、本地模型权重  
- 大样例文档可用 `samples/` 小文件；勿塞整本 Spring 手册进 Git  
