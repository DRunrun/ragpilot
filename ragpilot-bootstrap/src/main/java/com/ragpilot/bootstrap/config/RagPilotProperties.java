package com.ragpilot.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.ragpilot.core.retrieval.RetrievalMode;

/**
 * RagPilot 全部业务配置的集中入口，前缀 {@code ragpilot.*}。
 *
 * <p>链路位置：不属于 RAG 链路的任何一环，而是「组装层」的配置契约——
 * bootstrap 模块从这里读配置，再注入给 core 的分块器/检索器/生成器。
 *
 * <p>为什么存在：
 * <ul>
 *   <li>AGENTS.md 要求「配置项集中在 application.yml + RagPilotProperties，禁止魔法数散落」</li>
 *   <li>消融实验（M2）要频繁切换 chunk/retrieval 参数，集中一处才好对比</li>
 * </ul>
 *
 * <p>实现说明：用 record 做嵌套绑定（Spring Boot 3 的构造器绑定），
 * 字段天然 final、不可变，默认值直接写在构造器形参上。
 * 默认值口径与 {@code docs/m1-spec.md} §2「默认配置表」保持一致，改默认值必须两边同步。
 */
@ConfigurationProperties(prefix = "ragpilot")
public record RagPilotProperties(
        Chunk chunk,
        Retrieval retrieval,
        Generation generation,
        Embedding embedding,
        Refusal refusal,
        QueryRewrite queryRewrite
) {

    /** 紧凑构造器：任何嵌套段缺省时给出整套默认值，保证 yml 里可以只写要覆盖的键。 */
    public RagPilotProperties {
        chunk = chunk == null ? new Chunk(null, null) : chunk;
        retrieval = retrieval == null ? new Retrieval(null, null, null, null) : retrieval;
        generation = generation == null ? new Generation(null, null) : generation;
        embedding = embedding == null ? new Embedding(null) : embedding;
        refusal = refusal == null ? new Refusal(null) : refusal;
        queryRewrite = queryRewrite == null ? new QueryRewrite(null, null, null) : queryRewrite;
    }

    /**
     * 分块配置。对应 yml 键 {@code ragpilot.chunk.*}。
     *
     * @param size    每块目标字符数；默认 512——m1-spec 口径为 size-tokens，
     *                M1 实现按字符近似（中文一字约一 token，误差在 M2 评测时再收敛）
     * @param overlap 相邻块重叠字符数；默认 64，约为 size 的 12.5%，够保住跨边界的一句话
     */
    public record Chunk(Integer size, Integer overlap) {
        public Chunk {
            size = size == null ? 512 : size;
            overlap = overlap == null ? 64 : overlap;
        }
    }

    /**
     * 检索配置。对应 yml 键 {@code ragpilot.retrieval.*}。
     *
     * @param topK     向量召回条数；默认 5——太多稀释上下文，太少易漏召回
     * @param minScore 最低相似度阈值，低于它的命中视为无召回并走拒答；
     *                 默认 0.55——区分知识库内问题与库外噪声（见 application.yml 注释）
     * @param mode     检索模式；默认 VECTOR，与 M1 单路行为一致；消融时切 HYBRID / HYBRID_RERANK
     * @param rerank   重排序子配置；HYBRID_RERANK 时生效；enabled=false 则 NoOp
     */
    public record Retrieval(Integer topK, Double minScore, RetrievalMode mode, Rerank rerank) {
        public Retrieval {
            topK = topK == null ? 5 : topK;
            minScore = minScore == null ? 0.55 : minScore;
            mode = mode == null ? RetrievalMode.VECTOR : mode;
            rerank = rerank == null ? new Rerank(null, null, null) : rerank;
        }
    }

    /**
     * Rerank 子配置。对应 yml 键 {@code ragpilot.retrieval.rerank.*}。
     *
     * <p>F2.3 只装配可插拔实现；默认 {@code enabled=false} 走 {@code NoOpReranker}。
     * 接到主问答链路是 F2.4 的事（{@code HYBRID_RERANK} 模式）。
     *
     * @param enabled 是否启用 BGE 重排；false 时注入 NoOp，行为与 M1 一致
     * @param model   Cross-Encoder 模型 id，默认 bge-reranker-v2-m3
     * @param baseUrl Rerank 服务根地址（不含 /v1）；默认本机 8082，与 chat/embedding 网关分离
     */
    public record Rerank(Boolean enabled, String model, String baseUrl) {
        public Rerank {
            enabled = enabled == null ? Boolean.FALSE : enabled;
            model = model == null ? "bge-reranker-v2-m3" : model;
            baseUrl = baseUrl == null ? "http://127.0.0.1:8082" : baseUrl;
        }
    }

    /**
     * 生成（chat）配置。对应 yml 键 {@code ragpilot.generation.*}。
     *
     * @param model       LM Studio 中的 chat 模型 id；默认与 m1-spec 一致
     * @param temperature 采样温度；默认 0.0 保证评测可复现
     */
    public record Generation(String model, Double temperature) {
        public Generation {
            model = model == null ? "qwen3.6-35b-a3b-nvfp4" : model;
            temperature = temperature == null ? 0.0 : temperature;
        }
    }

    /**
     * 向量化（embedding）配置。对应 yml 键 {@code ragpilot.embedding.*}。
     *
     * @param model LM Studio 中的 embedding 模型 id；
     *              注意 nomic-embed 输出 768 维，与 PGVector 建表维度必须一致
     */
    public record Embedding(String model) {
        public Embedding {
            model = model == null ? "text-embedding-nomic-embed-text-v1.5" : model;
        }
    }

    /**
     * 拒答策略开关。对应 yml 键 {@code ragpilot.refusal.*}。
     *
     * @param emptyHits 检索无命中（或全部低于 minScore）时是否直接拒答；
     *                  默认 true——无证据绝不放行生成，这是 RAG 可信度的底线
     */
    public record Refusal(Boolean emptyHits) {
        public Refusal {
            emptyHits = emptyHits == null ? Boolean.TRUE : emptyHits;
        }
    }

    /**
     * 多轮检索查询改写配置。对应 yml 键 {@code ragpilot.query-rewrite.*}。
     *
     * <p>多轮会话里用 LLM 把带指代的问题改写成独立检索问句，
     * 超时/失败回退原文（见 core QueryRewriter）。
     *
     * @param enabled        是否启用改写；false 时检索直接用原问题（旧行为）
     * @param timeoutSeconds 改写调用超时秒数；默认 8——本地小模型改写在 2s 量级，
     *                       8s 足够又不至于拖垮首包
     * @param maxTurns       参与改写的最历史轮数；默认 3——指代通常 1、2 轮内可解，
     *                       更多只稀释提示词
     */
    public record QueryRewrite(Boolean enabled, Integer timeoutSeconds, Integer maxTurns) {
        public QueryRewrite {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            timeoutSeconds = timeoutSeconds == null ? 8 : timeoutSeconds;
            maxTurns = maxTurns == null ? 3 : maxTurns;
        }
    }
}
