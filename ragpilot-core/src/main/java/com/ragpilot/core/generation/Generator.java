package com.ragpilot.core.generation;

import reactor.core.publisher.Flux;

/**
 * 生成器端口：把组装好的 Prompt 变成流式答案 token。
 *
 * <p>链路位置：问答链路最后一环（检索 → Prompt 组装 → <b>生成</b>）。
 *
 * <p>为什么是接口：生成后端（LM Studio / Ollama / 云端）可替换，
 * core 的问答编排只依赖本端口；具体 OpenAI 兼容实现放 bootstrap。
 */
public interface Generator {

    /**
     * 流式生成。
     *
     * <p>契约：只输出正文 token；模型的思考内容（reasoning_content）不得混入，
     * 由实现方负责过滤（Qwen 思考模型踩过的坑）。
     *
     * @param prompt 完整 Prompt（含上下文与约束）
     * @return token 增量流；流正常结束表示生成完毕，异常以 error 信号传播给调用方
     */
    Flux<String> stream(String prompt);

    /**
     * 最近一次生成的 token 用量。
     *
     * <p>实现说明：本地网关多数在流的最后一帧返回 usage，
     * 因此本方法必须在 {@link #stream} 的流结束后调用才有意义。
     *
     * @return token 用量；网关未返回 usage 时为 {@code TokenUsage.ZERO}
     */
    AskTokenUsage lastUsage();

    /**
     * 单次生成的 token 用量（生成侧视角，避免 core 依赖 AskResult 的嵌套类型造成循环引用感）。
     *
     * @param prompt     输入 token 数
     * @param completion 输出 token 数
     */
    record AskTokenUsage(int prompt, int completion) {
        /** 未计量时的零值。 */
        public static final AskTokenUsage ZERO = new AskTokenUsage(0, 0);
    }
}
