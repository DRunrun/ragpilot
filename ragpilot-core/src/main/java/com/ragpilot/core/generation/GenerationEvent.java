package com.ragpilot.core.generation;

/**
 * 生成事件：流式生成过程中从上游冒出来的两类信号——正文增量 token 与流结束时的用量统计。
 *
 * <p>链路位置：生成端口（{@link Generator#stream}）的流元素契约。
 *
 * <p>为什么用事件而不是「流外共享变量」传 usage：旧版把 lastUsage 挂在生成器单例上，
 * 并发请求会互相覆盖 token 统计（A 的 done 事件读到 B 的用量）。
 * 事件流让 usage <b>跟着本次订阅走</b>，天然线程隔离，也让流元素自描述、可测。
 *
 * <p>用 sealed 接口：实现封闭可枚举，消费方（AskController）可以对两种事件做
 * 穷尽模式匹配，未来新增事件类型时编译器强制调用方处理。
 */
public sealed interface GenerationEvent permits GenerationEvent.Token, GenerationEvent.Completed {

    /**
     * 正文增量 token。
     *
     * <p>契约：只允许正文；模型思考内容（reasoning_content）不得混入，由实现方过滤。
     *
     * @param text 增量文本，非空非空白
     */
    record Token(String text) implements GenerationEvent {
        public Token {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("token text must not be empty");
            }
        }
    }

    /**
     * 流结束信号：携带本次生成的 token 用量。
     *
     * <p>顺序约定：必须是流的<b>最后一个</b>元素；网关未返回 usage 时携带
     * {@link Generator.AskTokenUsage#ZERO}，消费方照常处理，只是计分为 0。
     *
     * @param usage 本次生成的 token 用量，不得为 null
     */
    record Completed(Generator.AskTokenUsage usage) implements GenerationEvent {
        public Completed {
            usage = usage == null ? Generator.AskTokenUsage.ZERO : usage;
        }
    }

    /**
     * 便捷视图：是正文 token 时返回文本，其余事件返回 null。
     *
     * <p>配合 {@code Flux.mapNotNull} 过滤出纯正文流，避免每个消费方重复写模式匹配。
     */
    default String tokenTextOrNull() {
        return this instanceof Token t ? t.text() : null;
    }
}
