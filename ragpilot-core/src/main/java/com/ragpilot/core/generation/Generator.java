package com.ragpilot.core.generation;

import reactor.core.publisher.Flux;

/**
 * 生成器端口：把组装好的 Prompt 变成流式生成事件（正文 token + 结束用量）。
 *
 * <p>链路位置：问答链路最后一环（检索 → Prompt 组装 → <b>生成</b>）。
 *
 * <p>为什么是接口：生成后端（LM Studio / Ollama / 云端）可替换，
 * core 的问答编排只依赖本端口；具体 OpenAI 兼容实现放 bootstrap。
 *
 * <p>为什么事件流而不是「流 + lastUsage()」：旧契约把 token 用量存在生成器实例上，
 * 生成器是单例 Bean，并发请求会互相覆盖用量（计量串号）。
 * 现在用量装在 {@link GenerationEvent.Completed} 里随流返回，
 * 每次订阅各自独立，天然并发安全。
 */
public interface Generator {

    /**
     * 流式生成。
     *
     * <p>契约：只输出正文 {@link GenerationEvent.Token}；模型的思考内容
     * （reasoning_content）不得混入，由实现方负责过滤（Qwen 思考模型踩过的坑）。
     * 流正常结束时最后一个元素为 {@link GenerationEvent.Completed}；
     * 异常以 error 信号传播给调用方（此时没有 Completed 元素）。
     *
     * @param prompt 完整 Prompt（含上下文与约束）
     * @return 生成事件流
     */
    Flux<GenerationEvent> stream(String prompt);

    /**
     * 便捷视图：只关心正文的调用方用它，自动过滤掉 Completed 事件。
     *
     * @param prompt 完整 Prompt
     * @return 正文 token 增量流
     */
    default Flux<String> streamTokens(String prompt) {
        return stream(prompt).mapNotNull(GenerationEvent::tokenTextOrNull);
    }

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
