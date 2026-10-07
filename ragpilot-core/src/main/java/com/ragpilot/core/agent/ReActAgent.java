package com.ragpilot.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 自研 ReAct Agent：Thought → Action → Observation 循环，不依赖 LangGraph / 多 Agent 框架。
 *
 * <p>链路位置：M3 问答增强入口——可选替代「单次检索 + 生成」；仍落在 core，无 Spring。
 * <p>为什么自研：面试可讲清状态机与熔断；依赖方向保持 bootstrap → core。
 *
 * <p>状态机（每步循环）：
 * <ol>
 *   <li>DECISION：由 {@link DecisionMaker} 产出 thought + action</li>
 *   <li>FINISH：action={@code finish} → 结束，actionInput 为最终答案</li>
 *   <li>ACT：调用工具；失败重试 1 次；超过 {@code stepTimeout} → 超时熔断整次 run</li>
 *   <li>OBSERVE：把工具输出写入 observation，进入下一步</li>
 *   <li>STOP：达到 {@code maxSteps} 或超时 → 带着已有步骤返回</li>
 * </ol>
 */
public final class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    /** 默认最多推理步数：防止死循环；经验值 5 够「检索→再思考→作答」。 */
    public static final int DEFAULT_MAX_STEPS = 5;

    /** 默认单步超时：工具卡住时熔断，避免拖死整个请求。 */
    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 决策端口：生产环境由 LLM 实现；单测用 Mock 返回预定动作。
     * F3.1 只定义端口，不绑具体模型。
     */
    @FunctionalInterface
    public interface DecisionMaker {
        /**
         * @param question 用户问题
         * @param history  已完成的步骤（只读）
         * @return 下一步决策；不得返回 null
         */
        Decision decide(String question, List<AgentStep> history);
    }

    /**
     * 工具调用端口：F3.1 用 Mock；F3.2 再接到正式 {@code Tool} 接口。
     * 返回 {@link ToolObservation}：文本供下一步思考，结构化块供答案合成复用（免二次检索）。
     */
    @FunctionalInterface
    public interface ToolInvoker {
        /**
         * @param name  工具名
         * @param input 工具入参文本（通常是 JSON 或查询串）
         * @return 观察结果；不得为 null
         * @throws Exception 调用失败（会触发重试）
         */
        ToolObservation invoke(String name, String input) throws Exception;
    }

    /**
     * 单步决策。
     *
     * @param thought     思考过程
     * @param action      工具名或 {@link AgentStep#FINISH}
     * @param actionInput 工具参数 / 最终答案
     */
    public record Decision(String thought, String action, String actionInput) {
        public Decision {
            thought = thought == null ? "" : thought;
            action = action == null ? "" : action;
            actionInput = actionInput == null ? "" : actionInput;
        }

        public boolean isFinish() {
            return AgentStep.FINISH.equalsIgnoreCase(action);
        }
    }

    /**
     * 一次 Agent 运行结果。
     *
     * @param finalAnswer      最终答案；超时/触顶时可能为已有 observation 的拼接或空
     * @param steps            完整步骤列表
     * @param timedOut         是否因单步超时熔断
     * @param maxStepsReached  是否跑满 maxSteps 仍未 finish
     */
    public record AgentResult(
            String finalAnswer,
            List<AgentStep> steps,
            boolean timedOut,
            boolean maxStepsReached
    ) {
    }

    private final DecisionMaker decisionMaker;
    private final ToolInvoker toolInvoker;
    private final int maxSteps;
    private final Duration stepTimeout;

    public ReActAgent(DecisionMaker decisionMaker, ToolInvoker toolInvoker) {
        this(decisionMaker, toolInvoker, DEFAULT_MAX_STEPS, DEFAULT_STEP_TIMEOUT);
    }

    /**
     * @param decisionMaker 决策器
     * @param toolInvoker   工具执行器
     * @param maxSteps      最大步数，必须 &gt; 0
     * @param stepTimeout   单步（含一次重试窗口内的单次调用）超时
     */
    public ReActAgent(
            DecisionMaker decisionMaker,
            ToolInvoker toolInvoker,
            int maxSteps,
            Duration stepTimeout
    ) {
        this.decisionMaker = Objects.requireNonNull(decisionMaker, "decisionMaker");
        this.toolInvoker = Objects.requireNonNull(toolInvoker, "toolInvoker");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0");
        }
        this.maxSteps = maxSteps;
        this.stepTimeout = Objects.requireNonNull(stepTimeout, "stepTimeout");
        if (stepTimeout.isZero() || stepTimeout.isNegative()) {
            throw new IllegalArgumentException("stepTimeout must be positive");
        }
    }

    /**
     * 执行 ReAct 循环直到 finish / 超时 / 触顶。
     *
     * @param question 用户问题，不允许空白
     * @return 运行结果；steps 至少在异常路径也可能非空
     */
    public AgentResult run(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        List<AgentStep> steps = new ArrayList<>();
        // 专用线程池：给 Future.get(timeout) 用，跑完即关，避免泄漏
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "react-tool");
            t.setDaemon(true);
            return t;
        });

        try {
            for (int i = 1; i <= maxSteps; i++) {
                long stepStart = System.currentTimeMillis();

                // —— 状态：DECISION —— 根据历史决定下一步
                Decision decision = decisionMaker.decide(question, List.copyOf(steps));
                Objects.requireNonNull(decision, "decision");

                if (decision.isFinish()) {
                    // —— 状态：FINISH —— 直接收束，不再调工具
                    long ms = System.currentTimeMillis() - stepStart;
                    steps.add(new AgentStep(
                            i, decision.thought(), decision.action(), decision.actionInput(),
                            "", ms, false, false, false));
                    log.info("ReAct finish at step={}, answerLen={}", i, decision.actionInput().length());
                    return new AgentResult(decision.actionInput(), List.copyOf(steps), false, false);
                }

                // —— 状态：ACT —— 调工具；失败重试 1 次；单次调用受 stepTimeout 约束
                ToolCallOutcome outcome = invokeWithRetry(pool, decision.action(), decision.actionInput());
                long ms = System.currentTimeMillis() - stepStart;

                steps.add(new AgentStep(
                        i,
                        decision.thought(),
                        decision.action(),
                        decision.actionInput(),
                        outcome.observation().text(),
                        ms,
                        outcome.timedOut(),
                        outcome.retried(),
                        outcome.failed(),
                        outcome.observation().chunks()
                ));

                if (outcome.timedOut()) {
                    // —— 状态：TIMEOUT_BREAK —— 单步超时，整次 run 熔断
                    log.warn("ReAct step timeout at step={}, tool={}", i, decision.action());
                    return new AgentResult(
                            "",
                            List.copyOf(steps),
                            true,
                            false
                    );
                }

                if (outcome.failed()) {
                    // 两次都失败：记失败步后继续让决策器决定是否换工具或 finish
                    log.warn("ReAct tool failed after retry: step={}, tool={}", i, decision.action());
                }
                // —— 状态：OBSERVE 已写入 step；循环进入下一步 DECISION ——
            }

            // —— 状态：MAX_STEPS —— 触顶仍未 finish
            log.info("ReAct maxSteps={} reached without finish", maxSteps);
            return new AgentResult("", List.copyOf(steps), false, true);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 工具调用：首次失败再试 1 次；任一次超时则标记 timedOut。
     */
    private ToolCallOutcome invokeWithRetry(ExecutorService pool, String name, String input) {
        boolean retried = false;
        Exception last = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt == 1) {
                retried = true;
            }
            try {
                ToolObservation obs = runWithTimeout(pool, () -> toolInvoker.invoke(name, input));
                return new ToolCallOutcome(
                        obs == null ? ToolObservation.of("") : obs, false, retried, false);
            } catch (TimeoutException e) {
                return new ToolCallOutcome(ToolObservation.of(
                        "TIMEOUT: tool exceeded " + stepTimeout), true, retried, true);
            } catch (Exception e) {
                last = e;
                log.debug("Tool invoke failed attempt={}: {}", attempt + 1, e.getMessage());
            }
        }
        String msg = last == null ? "unknown error" : last.getMessage();
        return new ToolCallOutcome(
                ToolObservation.of("ERROR: " + msg), false, true, true);
    }

    private ToolObservation runWithTimeout(ExecutorService pool, Callable<ToolObservation> task) throws Exception {
        Future<ToolObservation> future = pool.submit(task);
        try {
            return future.get(stepTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException(cause);
        }
    }

    private record ToolCallOutcome(
            ToolObservation observation,
            boolean timedOut,
            boolean retried,
            boolean failed
    ) {
    }
}
