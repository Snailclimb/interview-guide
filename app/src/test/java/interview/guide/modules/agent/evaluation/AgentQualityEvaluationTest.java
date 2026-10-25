package interview.guide.modules.agent.evaluation;

import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.service.AgentLoopEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Agent 样本评测入口。
 *
 * <p>普通测试不调用模型；传入 {@code -PagentEvalInput=...} 后才读取外部 JSONL，
 * 生成 JSON/Markdown 报告，并把质量违规作为测试失败。</p>
 */
@Tag("agent-eval")
@DisplayName("Agent 真实样本质量评测")
class AgentQualityEvaluationTest {

    private static final String DATASET = "agent-eval/dataset-v1.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentQualityEvaluator evaluator = new AgentQualityEvaluator();

    @Test
    @DisplayName("读取真实运行结果并生成可比较报告")
    void evaluatesProvidedRunSamples() throws IOException {
        String input = System.getProperty("agentEval.input", "").trim();
        Assumptions.assumeTrue(!input.isBlank(), "未提供 agentEval.input，跳过真实模型评测");
        Path inputPath = Path.of(input);
        Assumptions.assumeTrue(Files.isRegularFile(inputPath), "评测输入文件不存在: " + inputPath);

        Map<String, AgentQualityEvaluator.Sample> samples = loadDataset();
        List<RunSample> runs = loadRuns(inputPath);
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        double totalScore = 0.0;
        long promptTokens = 0;
        long completionTokens = 0;
        double totalCost = 0.0;
        for (RunSample run : runs) {
            assertThat(ids.add(run.sampleId())).as("评测样本重复: %s", run.sampleId()).isTrue();
            AgentQualityEvaluator.Sample sample = samples.get(run.sampleId());
            assertThat(sample).as("未知评测样本: %s", run.sampleId()).isNotNull();
            AgentQualityEvaluator.Evaluation evaluation = evaluator.evaluate(
                sample, run.answer(), run.sources(), run.loopIterations()
            );
            if (evaluation.passed()) {
                passed++;
            }
            totalScore += evaluation.overallScore();
            promptTokens += positive(run.promptTokens());
            completionTokens += positive(run.completionTokens());
            totalCost += run.cost() == null ? 0.0 : Math.max(0.0, run.cost());
            results.add(resultMap(sample, run, evaluation));
        }
        assertThat(runs).isNotEmpty();

        String runId = System.getProperty(
            "agentEval.runId", "agent-eval-" + System.currentTimeMillis()
        );
        Path reportDirectory = Path.of(System.getProperty(
            "agentEval.reportDir", "build/reports/agent-eval"
        ));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("evaluatorVersion", AgentQualityEvaluator.VERSION);
        report.put("datasetVersion", DATASET);
        report.put("timestamp", Instant.now().toString());
        report.put("environment", environment(runs));
        report.put("summary", Map.of(
            "samples", runs.size(),
            "passed", passed,
            "failed", runs.size() - passed,
            "passRate", (double) passed / runs.size(),
            "averageScore", totalScore / runs.size(),
            "promptTokens", promptTokens,
            "completionTokens", completionTokens,
            "totalTokens", promptTokens + completionTokens,
            "totalCost", totalCost
        ));
        report.put("samples", results);
        AgentQualityReportWriter.write(reportDirectory, runId, report);

        assertThat(passed)
            .as("Agent 质量样本存在违规，详情见 %s", reportDirectory.resolve(runId + ".md"))
            .isEqualTo(runs.size());
    }

    private Map<String, AgentQualityEvaluator.Sample> loadDataset() throws IOException {
        String jsonl = new String(
            new ClassPathResource(DATASET).getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        Map<String, AgentQualityEvaluator.Sample> result = new HashMap<>();
        for (String line : jsonl.lines().filter(value -> !value.isBlank()).toList()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(line, Map.class);
            AgentQualityEvaluator.Sample sample = AgentQualityEvaluator.Sample.fromMap(raw);
            result.put(sample.id(), sample);
        }
        return result;
    }

    private List<RunSample> loadRuns(Path path) throws IOException {
        List<RunSample> result = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(line, Map.class);
            result.add(RunSample.fromMap(raw));
        }
        return result;
    }

    private Map<String, Object> resultMap(
        AgentQualityEvaluator.Sample sample,
        RunSample run,
        AgentQualityEvaluator.Evaluation evaluation
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleId", sample.id());
        result.put("split", sample.split());
        result.put("provider", run.provider());
        result.put("model", run.model());
        result.put("promptVersion", run.promptVersion());
        result.put("codeVersion", run.codeVersion());
        result.put("promptTokens", run.promptTokens());
        result.put("completionTokens", run.completionTokens());
        result.put("cost", run.cost());
        result.put("passed", evaluation.passed());
        result.put("overallScore", evaluation.overallScore());
        result.put("scores", evaluation.scores());
        result.put("violations", evaluation.violations());
        return result;
    }

    private Map<String, Object> environment(List<RunSample> runs) {
        RunSample first = runs.getFirst();
        return Map.of(
            "provider", first.provider(),
            "model", first.model(),
            "promptVersion", first.promptVersion(),
            "codeVersion", first.codeVersion()
        );
    }

    private long positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record RunSample(
        String sampleId,
        String provider,
        String model,
        String promptVersion,
        String codeVersion,
        String answer,
        Integer promptTokens,
        Integer completionTokens,
        Double cost,
        List<AgentSource> sources,
        List<AgentLoopEngine.LoopIteration> loopIterations
    ) {
        @SuppressWarnings("unchecked")
        private static RunSample fromMap(Map<String, Object> raw) {
            List<AgentSource> sources = ((List<Map<String, Object>>) raw.getOrDefault("sources", List.of()))
                .stream()
                .map(source -> new AgentSource(
                    (String) source.get("kind"),
                    (String) source.get("title"),
                    source.get("score") instanceof Number score ? score.doubleValue() : null,
                    (String) source.get("reference")
                )).toList();
            List<AgentLoopEngine.LoopIteration> iterations =
                ((List<Map<String, Object>>) raw.getOrDefault("loopIterations", List.of()))
                    .stream()
                    .map(item -> new AgentLoopEngine.LoopIteration(
                        item.get("iteration") instanceof Number number ? number.intValue() : 0,
                        (String) item.get("action"),
                        (String) item.get("observation")
                    )).toList();
            return new RunSample(
                required(raw, "sampleId"),
                required(raw, "provider"),
                required(raw, "model"),
                required(raw, "promptVersion"),
                required(raw, "codeVersion"),
                (String) raw.getOrDefault("answer", ""),
                number(raw.get("promptTokens")),
                number(raw.get("completionTokens")),
                raw.get("cost") instanceof Number cost ? cost.doubleValue() : null,
                sources,
                iterations
            );
        }

        private static String required(Map<String, Object> raw, String key) {
            Object value = raw.get(key);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("真实评测结果缺少字段: " + key);
            }
            return text;
        }

        private static Integer number(Object value) {
            return value instanceof Number number ? number.intValue() : null;
        }
    }
}
