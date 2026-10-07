package com.ragpilot.bootstrap.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ragpilot.bootstrap.config.RagPilotProperties;
import com.ragpilot.bootstrap.ingest.SpringAiVectorStoreWriter;
import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.SourceDocument;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.core.generation.RefusalPolicy;
import com.ragpilot.core.ingestion.ChunkStrategy;
import com.ragpilot.core.ingestion.Chunker;
import com.ragpilot.core.ingestion.ChunkerFactory;
import com.ragpilot.core.ingestion.DocumentParser;
import com.ragpilot.core.ingestion.IngestionPipeline;
import com.ragpilot.core.ingestion.MarkdownParser;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 评测 CLI：{@code java -jar ... eval --mode=HYBRID}。
 *
 * <p>链路位置：bootstrap 组装层入口；真正指标计算在 {@link EvalRunner}。
 * <p>检测到非选项参数 {@code eval} 时跑评测、写 JSON 结果并退出；
 * 否则本 Runner 空操作，不影响正常 Web 启动。
 *
 * <p>分块消融臂：{@code --reingest --chunk-strategy=HEADING --chunk-size=256 --chunk-overlap=32}
 * 先用指定分块参数重灌语料（按 docId 清旧块再写），再跑评测；
 * 报告贴 {@code label=chunk=HEADING/256/32} 便于和基线臂同模式区分归因。
 * 不传 {@code --reingest} 则行为与旧版完全一致。
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
    private final SpringAiVectorStoreWriter chunkWriter;
    private final RagPilotProperties props;
    private final int defaultTopK;

    public EvalCommand(
            ModeAwareRetriever retriever,
            PromptBuilder promptBuilder,
            Generator generator,
            RefusalPolicy refusalPolicy,
            JudgeLlm judgeLlm,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext context,
            SpringAiVectorStoreWriter chunkWriter,
            RagPilotProperties props
    ) {
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.refusalPolicy = refusalPolicy;
        this.judgeLlm = judgeLlm;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.context = context;
        this.chunkWriter = chunkWriter;
        this.props = props;
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
        String label = resolveLabel(args);
        Path out = Path.of(firstOption(args, "out",
                "reports/eval-" + mode.name() + (label == null ? "" : "-" + sanitizeForFileName(label)) + ".json"));

        log.info("Eval starting: mode={}, topK={}, golden={}, judge={}, label={}, out={}",
                mode, topK, goldenPath, withJudge, label, out.toAbsolutePath());

        // 分块消融臂：先按临时分块参数重灌语料，再评测；只影响本次进程，不动运行时配置
        if (args.containsOption("reingest")) {
            reingestWithChunkArgs(args);
        }

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
        if (label != null) {
            report = report.withLabel(label);
        }

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
        String answer = generator.streamTokens(prompt).collectList().blockOptional()
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

    /**
     * 消融臂标签：--label 显式指定；否则若 --reingest 存在自动生成 chunk=策略/尺寸/重叠。
     */
    private String resolveLabel(ApplicationArguments args) {
        String explicit = firstOption(args, "label", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        if (!args.containsOption("reingest")) {
            return null;
        }
        return "chunk=" + parseStrategy(args).name() + "/"
                + parseIntOption(args, "chunk-size", props.chunk().size()) + "/"
                + parseIntOption(args, "chunk-overlap", props.chunk().overlap());
    }

    private static ChunkStrategy parseStrategy(ApplicationArguments args) {
        String raw = firstOption(args, "chunk-strategy", "FIXED");
        try {
            return ChunkStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown --chunk-strategy=" + raw + " (FIXED|HEADING|RECURSIVE)", e);
        }
    }

    /**
     * 按临时分块参数重灌语料：删旧块 → 新策略切块 → 写回。
     *
     * <p>为什么先删后写：写入侧 Document.id 是随机 UUID，upsert 无法覆盖旧块；
     * 换块尺寸后旧 doc#N 还会残留污染检索。语料默认取 samples/ 下全部可解析文件，
     * 也可 --reingest=path1,path2 指定子集。
     */
    private void reingestWithChunkArgs(ApplicationArguments args) throws IOException {
        int size = parseIntOption(args, "chunk-size", props.chunk().size());
        int overlap = parseIntOption(args, "chunk-overlap", props.chunk().overlap());
        Chunker chunker = ChunkerFactory.create(parseStrategy(args), size, overlap, null);
        IngestionPipeline pipeline = new IngestionPipeline(chunker, chunkWriter);
        DocumentParser parser = new MarkdownParser();

        List<Path> files = resolveReingestFiles(args, parser);
        log.info("Re-ingesting {} file(s) with chunker {} size={} overlap={}",
                files.size(), parseStrategy(args), size, overlap);
        for (Path file : files) {
            SourceDocument doc = parser.parse(file);
            chunkWriter.deleteByDocId(doc.id());
            pipeline.ingest(doc);
        }
    }

    /** --reingest 带值时为逗号分隔的文件列表；不带值默认扫描 samples/。 */
    private static List<Path> resolveReingestFiles(ApplicationArguments args, DocumentParser parser)
            throws IOException {
        List<Path> files = new ArrayList<>();
        List<String> values = args.getOptionValues("reingest");
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            for (String part : values.get(0).split(",")) {
                Path p = Path.of(part.strip());
                if (!Files.isRegularFile(p) || !parser.supports(p)) {
                    throw new IllegalArgumentException("--reingest 文件不可用: " + part);
                }
                files.add(p);
            }
            return files;
        }
        Path samplesDir = Path.of("samples");
        if (!Files.isDirectory(samplesDir)) {
            throw new IllegalArgumentException("--reingest 未指定文件且 samples/ 不存在");
        }
        try (Stream<Path> stream = Files.list(samplesDir)) {
            stream.filter(parser::supports).sorted().forEach(files::add);
        }
        return files;
    }

    /** 标签里的 / 等字符换 _，避免被当路径分隔符。 */
    private static String sanitizeForFileName(String label) {
        return label.replaceAll("[^A-Za-z0-9._-]", "_");
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
