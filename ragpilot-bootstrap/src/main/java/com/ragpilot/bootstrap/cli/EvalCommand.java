package com.ragpilot.bootstrap.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.core.generation.RefusalPolicy;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.eval.dataset.GoldenQuestion;
import com.ragpilot.eval.dataset.GoldenSetLoader;
import com.ragpilot.eval.judge.FaithfulnessJudge;
import com.ragpilot.eval.judge.JudgeLlm;
import com.ragpilot.eval.judge.JudgeScore;
import com.ragpilot.eval.judge.RelevancyJudge;
import com.ragpilot.eval.runner.EvalRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 评测 CLI：{@code java -jar ... eval --mode=HYBRID}。
 *
 * <p>链路位置：bootstrap 组装层入口；真正指标计算在 {@link EvalRunner}。
 * <p>检测到非选项参数 {@code eval} 时跑评测、写 JSON 结果并退出；
 * 否则本 Runner 空操作，不影响正常 Web 启动。
 */
@Component
@Order(0)
public class EvalCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCommand.class);

    private final ModeAwareRetriever retriever;
    private final PromptBuilder promptBuilder;
    private final Generator generator;
    private final RefusalPolicy refusalPolicy;
    private final JudgeLlm judgeLlm;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;
    private final int defaultTopK;

    public EvalCommand(
            ModeAwareRetriever retriever,
            PromptBuilder promptBuilder,
            Generator generator,
            RefusalPolicy refusalPolicy,
            JudgeLlm judgeLlm,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext context,
            com.ragpilot.bootstrap.config.RagPilotProperties props
    ) {
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.refusalPolicy = refusalPolicy;
        this.judgeLlm = judgeLlm;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.context = context;
        this.defaultTopK = props.retrieval().topK();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty() || !"eval".equalsIgnoreCase(nonOption.get(0))) {
            return;
        }

        RetrievalMode mode = parseMode(args);
        int topK = parseIntOption(args, "topK", defaultTopK);
        String goldenPath = firstOption(args, "golden", "golden/v1.0.jsonl");
        boolean withJudge = args.containsOption("judge");
        Path out = Path.of(firstOption(args, "out",
                "reports/eval-" + mode.name() + ".json"));

        log.info("Eval starting: mode={}, topK={}, golden={}, judge={}, out={}",
                mode, topK, goldenPath, withJudge, out.toAbsolutePath());

        List<GoldenQuestion> golden = new GoldenSetLoader().loadClasspath(goldenPath);

        EvalRunner.GenerationPort generation = null;
        EvalRunner.JudgePort judge = null;
        if (withJudge) {
            generation = this::generateAnswer;
            FaithfulnessJudge faith = new FaithfulnessJudge(judgeLlm);
            RelevancyJudge relevancy = new RelevancyJudge(judgeLlm);
            judge = new EvalRunner.JudgePort() {
                @Override
                public JudgeScore faithfulness(String question, String answer, List<String> contexts) {
                    return faith.score(question, answer, contexts);
                }

                @Override
                public JudgeScore relevancy(String question, String answer) {
                    return relevancy.score(question, answer);
                }
            };
        }

        EvalRunner runner = new EvalRunner(
                (q, k, m) -> retriever.retrieve(q, k, m),
                generation,
                judge
        );
        EvalRunner.EvalReport report = runner.run(mode, golden, topK);

        Files.createDirectories(out.getParent() == null ? Path.of(".") : out.getParent());
        objectMapper.writeValue(out.toFile(), report);
        log.info("Eval done: questions={}, recallAtK={}, mrr={}, out={}",
                report.questionCount(), report.recallAtK(), report.mrr(), out.toAbsolutePath());

        // 评测是批处理任务：写完即退出，不要挂着 Web 端口
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private Optional<String> generateAnswer(String question, List<RetrievedChunk> hits) {
        if (refusalPolicy.checkBeforeGeneration(hits, "eval").isPresent()) {
            return Optional.empty();
        }
        String prompt = promptBuilder.build(question, hits).orElse(null);
        if (prompt == null) {
            return Optional.empty();
        }
        String answer = generator.stream(prompt).collectList().blockOptional()
                .map(tokens -> String.join("", tokens))
                .orElse("");
        if (refusalPolicy.checkAfterGeneration(answer, "eval").isPresent()) {
            return Optional.empty();
        }
        return Optional.of(answer);
    }

    private static RetrievalMode parseMode(ApplicationArguments args) {
        String raw = firstOption(args, "mode", "VECTOR");
        try {
            return RetrievalMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown --mode=" + raw + " (VECTOR|HYBRID|HYBRID_RERANK)", e);
        }
    }

    private static int parseIntOption(ApplicationArguments args, String name, int defaultValue) {
        String raw = firstOption(args, name, null);
        if (raw == null) {
            return defaultValue;
        }
        return Integer.parseInt(raw);
    }

    private static String firstOption(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }
}
