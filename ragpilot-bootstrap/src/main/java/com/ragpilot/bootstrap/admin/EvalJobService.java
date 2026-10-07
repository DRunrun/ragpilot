package com.ragpilot.bootstrap.admin;

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
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 评测异步任务（ADM-4.1～4.2）：包装 EvalRunner，状态机 PENDING→RUNNING→DONE/FAILED。
 */
@Service
public class EvalJobService {

    private static final Logger log = LoggerFactory.getLogger(EvalJobService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ModeAwareRetriever retriever;
    private final PromptBuilder promptBuilder;
    private final Generator generator;
    private final RefusalPolicy refusalPolicy;
    private final JudgeLlm judgeLlm;
    private final ObjectMapper objectMapper;
    private final int defaultTopK;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-job");
        t.setDaemon(true);
        return t;
    });

    public EvalJobService(
            JdbcTemplate jdbcTemplate,
            ModeAwareRetriever retriever,
            PromptBuilder promptBuilder,
            Generator generator,
            RefusalPolicy refusalPolicy,
            JudgeLlm judgeLlm,
            ObjectMapper objectMapper,
            com.ragpilot.bootstrap.config.RagPilotProperties props
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.refusalPolicy = refusalPolicy;
        this.judgeLlm = judgeLlm;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.defaultTopK = props.retrieval().topK();
    }

    public record EvalJobView(
            String id,
            String status,
            String mode,
            String goldenSet,
            int topK,
            boolean withJudge,
            int progress,
            String resultPath,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt
    ) {
    }

    private final RowMapper<EvalJobView> mapper = (rs, i) -> new EvalJobView(
            rs.getString("id"),
            rs.getString("status"),
            rs.getString("mode"),
            rs.getString("golden_set"),
            rs.getInt("top_k"),
            rs.getBoolean("with_judge"),
            rs.getInt("progress"),
            rs.getString("result_path"),
            rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class)
    );

    public EvalJobView create(String mode, String goldenSet, Integer topK, boolean withJudge) {
        RetrievalMode m = parseMode(mode);
        String gs = (goldenSet == null || goldenSet.isBlank()) ? "v1.0" : goldenSet.strip();
        int k = topK == null ? defaultTopK : topK;
        String id = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update(
                """
                        INSERT INTO eval_job(id, status, mode, golden_set, top_k, with_judge, progress, created_at, updated_at)
                        VALUES (?, 'PENDING', ?, ?, ?, ?, 0, NOW(), NOW())
                        """,
                id, m.name(), gs, k, withJudge);
        executor.submit(() -> runJob(id));
        return get(id).orElseThrow();
    }

    public Optional<EvalJobView> get(String id) {
        List<EvalJobView> rows = jdbcTemplate.query(
                """
                        SELECT id, status, mode, golden_set, top_k, with_judge, progress,
                               result_path, error_message, created_at, finished_at
                        FROM eval_job WHERE id = ?
                        """,
                mapper, id);
        return rows.stream().findFirst();
    }

    public List<EvalJobView> list(int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return jdbcTemplate.query(
                """
                        SELECT id, status, mode, golden_set, top_k, with_judge, progress,
                               result_path, error_message, created_at, finished_at
                        FROM eval_job
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                mapper, lim);
    }

    public Optional<String> reportMarkdown(String id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT report_markdown FROM eval_job WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.getFirst());
    }

    public Map<String, Object> goldenMeta(String goldenSet) {
        String gs = (goldenSet == null || goldenSet.isBlank()) ? "v1.0" : goldenSet.strip();
        String path = gs.startsWith("golden/") ? gs : "golden/" + gs + ".jsonl";
        List<GoldenQuestion> questions = new GoldenSetLoader().loadClasspath(path);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goldenSet", gs);
        data.put("resource", path);
        data.put("count", questions.size());
        data.put("samples", questions.stream().limit(3).map(q -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", q.id());
            s.put("question", q.question());
            s.put("expectedDocIds", q.expectedDocIds());
            return s;
        }).toList());
        return data;
    }

    private void runJob(String id) {
        try {
            updateRunning(id, 5);
            EvalJobView job = get(id).orElseThrow();
            RetrievalMode mode = parseMode(job.mode());
            String path = job.goldenSet().startsWith("golden/")
                    ? job.goldenSet()
                    : "golden/" + job.goldenSet() + ".jsonl";
            List<GoldenQuestion> golden = new GoldenSetLoader().loadClasspath(path);

            EvalRunner.GenerationPort generation = null;
            EvalRunner.JudgePort judgePort = null;
            if (job.withJudge()) {
                generation = this::generateAnswer;
                FaithfulnessJudge faith = new FaithfulnessJudge(judgeLlm);
                RelevancyJudge relevancy = new RelevancyJudge(judgeLlm);
                judgePort = new EvalRunner.JudgePort() {
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
                    (q, topK, m) -> retriever.retrieve(q, topK, m),
                    generation,
                    judgePort
            );

            updateRunning(id, 20);
            EvalRunner.EvalReport report = runner.run(mode, golden, job.topK());
            updateRunning(id, 80);

            Path outDir = Path.of("reports");
            Files.createDirectories(outDir);
            Path jsonPath = outDir.resolve(id + ".json");
            Path mdPath = outDir.resolve(id + ".md");
            objectMapper.writeValue(jsonPath.toFile(), report);
            String md = toSimpleMarkdown(report);
            Files.writeString(mdPath, md);

            jdbcTemplate.update(
                    """
                            UPDATE eval_job
                            SET status = 'DONE', progress = 100, result_path = ?,
                                report_markdown = ?, error_message = NULL,
                                updated_at = NOW(), finished_at = NOW()
                            WHERE id = ?
                            """,
                    mdPath.toString(), md, id);
            log.info("Eval job done: {}", id);
        } catch (Exception e) {
            log.warn("Eval job failed {}: {}", id, e.toString());
            jdbcTemplate.update(
                    """
                            UPDATE eval_job
                            SET status = 'FAILED', progress = 100, error_message = ?,
                                updated_at = NOW(), finished_at = NOW()
                            WHERE id = ?
                            """,
                    e.getMessage(), id);
        }
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

    private void updateRunning(String id, int progress) {
        jdbcTemplate.update(
                """
                        UPDATE eval_job
                        SET status = 'RUNNING', progress = ?, updated_at = NOW()
                        WHERE id = ?
                        """,
                progress, id);
    }

    private static String toSimpleMarkdown(EvalRunner.EvalReport report) {
        return """
                # Eval Report · %s
                
                | metric | value |
                |---|---:|
                | mode | %s |
                | topK | %d |
                | questions | %d |
                | Recall@k | %s |
                | MRR | %s |
                | Faithfulness | %s |
                | AnswerRelevancy | %s |
                | RefusalAccuracy | %s |
                
                generatedAt: %s
                """.formatted(
                report.mode(),
                report.mode(),
                report.topK(),
                report.questionCount(),
                fmt(report.recallAtK()),
                fmt(report.mrr()),
                fmt(report.faithfulness()),
                fmt(report.answerRelevancy()),
                fmt(report.refusalAccuracy()),
                report.generatedAt()
        );
    }

    private static String fmt(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static RetrievalMode parseMode(String mode) {
        try {
            return RetrievalMode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 mode: " + mode + "（VECTOR|HYBRID|HYBRID_RERANK）");
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
