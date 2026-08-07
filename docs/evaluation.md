# Evaluation 规格

> 评测是 RagPilot 差异化核心。M1 只需黄金集 **v0.1（≥10 题）**；M2 定稿 **50 题** + 自动化指标。

## 1. 黄金集 Schema

文件建议：`ragpilot-eval/src/main/resources/golden/spring-qa-v0.1.jsonl`  
每行一个 JSON 对象：

```json
{
  "id": "q001",
  "question": "Spring Bean 的生命周期包含哪些主要阶段？",
  "reference_answer": "……（可选，Faithfulness/人工对照用）",
  "expected_doc_ids": ["spring-bean-lifecycle"],
  "expected_chunk_ids": [],
  "tags": ["lifecycle", "beans"],
  "difficulty": "easy"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 稳定 ID，回归对比用 |
| `question` | 是 | 用户问题 |
| `reference_answer` | 否 | LLM-as-Judge / 人工用 |
| `expected_doc_ids` | 建议 | 检索 Recall 用 |
| `expected_chunk_ids` | 否 | 有则算更严的召回 |
| `tags` | 否 | 筛选子集 |
| `difficulty` | 否 | easy/medium/hard |

## 2. 指标定义（M2）

| 指标 | 定义（本项目口径） | 实现 |
|---|---|---|
| **Recall@k** | `expected_doc_ids` 是否出现在检索 top-k 的 docId 集合中（文档级） | 规则计算 |
| **Faithfulness** | 答案陈述是否被检索上下文支持 | LLM-as-Judge（本地 32B），prompt 固定、temperature=0 |
| **Answer Relevancy** | 答案是否针对问题 | LLM-as-Judge |

Judge Prompt 与打分量表放 `ragpilot-eval/.../judge/`，**变更 Judge Prompt 本身也要记版本号**。

## 3. 消融实验矩阵（M2 README 用）

至少跑满：

| 实验 | 变量 |
|---|---|
| A | 仅向量 vs 混合（BM25+向量+RRF） |
| B | 无 Rerank vs 有 Rerank |
| C（可选） | chunk 512 vs 1024 |

报告输出：`reports/ablation-YYYYMMDD.md`，表头固定：

```
| setup | Recall@5 | Faithfulness | AnswerRelevancy | notes |
```

## 4. 回归门禁（M2 起）

- 改 Chunk / Prompt / 检索参数后，跑同一黄金集  
- Faithfulness 相对基线下降 **> 0.05** → 视为回归失败（阈值可配置）  
- 报告必须可进 Git（或 CI artifact）

## 5. M1 最低要求

- [ ] 手写 ≥10 条 `golden` 题（可无自动化指标）  
- [ ] 人工跑 5 题，记录「有引用 / 拒答是否合理」  
