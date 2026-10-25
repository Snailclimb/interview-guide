package interview.guide.modules.agent.evaluation;

import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.service.AgentLoopEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 的可重复质量基线评估器。
 *
 * <p>这里只做可自动验证的质量门禁，不把字符串命中率包装成“语义理解”。
 * 它用于发现 Prompt、模型或 Loop 改动带来的明显回归；事实是否真正正确，
 * 仍需要人工或单独的模型评审。</p>
 */
public final class AgentQualityEvaluator {

    public static final String VERSION = "agent-quality-v1";
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[R\\d+\\]", Pattern.CASE_INSENSITIVE);

    public Evaluation evaluate(
        Sample sample,
        String answer,
        List<AgentSource> sources,
        List<AgentLoopEngine.LoopIteration> loopIterations
    ) {
        if (sample == null) {
            throw new IllegalArgumentException("评测样本不能为空");
        }
        String safeAnswer = answer == null ? "" : answer;
        List<String> violations = new ArrayList<>();

        int requiredFactsHit = 0;
        for (String fact : sample.requiredFacts()) {
            if (containsNormalized(safeAnswer, fact)) {
                requiredFactsHit++;
            } else {
                violations.add("缺少必要事实: " + fact);
            }
        }
        for (String fact : sample.forbiddenFacts()) {
            if (containsNormalized(safeAnswer, fact)) {
                violations.add("出现禁止事实: " + fact);
            }
        }

        int headingsHit = 0;
        for (String section : sample.requiredSections()) {
            if (hasHeading(safeAnswer, section)) {
                headingsHit++;
            } else {
                violations.add("缺少 Markdown 标题: " + section);
            }
        }
        double completeness = ratio(requiredFactsHit, sample.requiredFacts().size())
            * ratio(headingsHit, sample.requiredSections().size());
        double factConsistency = sample.forbiddenFacts().stream()
            .noneMatch(fact -> containsNormalized(safeAnswer, fact)) ? 1.0 : 0.0;

        double grounding = evaluateGrounding(sample, safeAnswer, sources, violations);
        double markdown = evaluateMarkdown(safeAnswer, violations);
        double loop = evaluateLoop(sample, loopIterations, violations);
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("completeness", completeness);
        scores.put("factConsistency", factConsistency);
        scores.put("grounding", grounding);
        scores.put("markdown", markdown);
        scores.put("loop", loop);
        double overall = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new Evaluation(sample.id(), VERSION, scores, overall, List.copyOf(violations));
    }

    private double evaluateGrounding(
        Sample sample,
        String answer,
        List<AgentSource> sources,
        List<String> violations
    ) {
        Set<String> allowed = sources == null
            ? Set.of()
            : sources.stream()
                .filter(source -> source != null && "rag".equalsIgnoreCase(source.kind()))
                .map(AgentSource::reference)
                .filter(reference -> reference != null && !reference.isBlank())
                .map(reference -> reference.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> cited = CITATION_PATTERN.matcher(answer).results()
            .map(match -> match.group().substring(1, match.group().length() - 1)
                .toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        Set<String> invalid = cited.stream().filter(reference -> !allowed.contains(reference)).collect(
            java.util.stream.Collectors.toSet()
        );
        if (!invalid.isEmpty()) {
            violations.add("引用了未检索到的来源: " + String.join("、", invalid));
            return 0.0;
        }
        if (!sample.ragRequired()) {
            return 1.0;
        }
        if (allowed.isEmpty()) {
            violations.add("样本要求 RAG 依据，但没有可引用来源");
            return 0.0;
        }
        if (cited.isEmpty()) {
            violations.add("检索到 RAG 来源但回答没有引用");
            return 0.0;
        }
        return 1.0;
    }

    private double evaluateMarkdown(String answer, List<String> violations) {
        if (answer.isBlank()) {
            violations.add("回答为空");
            return 0.0;
        }
        if (answer.contains("\\n")) {
            violations.add("回答包含未渲染的换行转义");
            return 0.0;
        }
        long fenceCount = answer.lines().filter(line -> line.trim().startsWith("```")).count();
        if (fenceCount % 2 != 0) {
            violations.add("Markdown 代码围栏未闭合");
            return 0.0;
        }
        return 1.0;
    }

    private double evaluateLoop(
        Sample sample,
        List<AgentLoopEngine.LoopIteration> iterations,
        List<String> violations
    ) {
        List<AgentLoopEngine.LoopIteration> safeIterations = iterations == null ? List.of() : iterations;
        int failures = 0;
        if (safeIterations.size() > sample.maxLoopSteps()) {
            violations.add("Loop 步骤超过预算: " + safeIterations.size() + ">" + sample.maxLoopSteps());
            failures++;
        }
        for (AgentLoopEngine.LoopIteration iteration : safeIterations) {
            if (iteration == null || iteration.action() == null || iteration.observation() == null
                || iteration.action().isBlank() || iteration.observation().isBlank()) {
                violations.add("Loop 存在空动作或空观察");
                failures++;
                continue;
            }
            if (!sample.allowedLoopActions().isEmpty()
                && sample.allowedLoopActions().stream().noneMatch(iteration.action()::contains)) {
                violations.add("Loop 动作越界: " + iteration.action());
                failures++;
            }
        }
        int checks = Math.max(1, safeIterations.size() + 1);
        return Math.max(0.0, (double) (checks - failures) / checks);
    }

    private boolean hasHeading(String answer, String section) {
        String expected = normalize(section);
        return answer.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("#"))
            .map(line -> line.replaceFirst("^#+\\s*", ""))
            .map(this::normalize)
            .anyMatch(expected::equals);
    }

    private boolean containsNormalized(String answer, String expected) {
        return !normalize(expected).isBlank() && normalize(answer).contains(normalize(expected));
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.replaceAll("\\s+", "")
                .replace("*", "")
                .replace("#", "")
                .replace("`", "")
                .toLowerCase(Locale.ROOT);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    public record Sample(
        String id,
        String goal,
        String message,
        String split,
        List<String> requiredFacts,
        List<String> forbiddenFacts,
        List<String> requiredSections,
        boolean ragRequired,
        int maxLoopSteps,
        List<String> allowedLoopActions
    ) {
        public Sample {
            id = id == null ? "" : id;
            goal = goal == null ? "" : goal;
            message = message == null ? "" : message;
            split = split == null ? "dev" : split;
            requiredFacts = safeList(requiredFacts);
            forbiddenFacts = safeList(forbiddenFacts);
            requiredSections = safeList(requiredSections);
            allowedLoopActions = safeList(allowedLoopActions);
            maxLoopSteps = Math.max(0, maxLoopSteps);
        }

        @SuppressWarnings("unchecked")
        public static Sample fromMap(Map<String, Object> raw) {
            return new Sample(
                (String) raw.get("id"),
                (String) raw.get("goal"),
                (String) raw.get("message"),
                (String) raw.getOrDefault("split", "dev"),
                (List<String>) raw.getOrDefault("requiredFacts", List.of()),
                (List<String>) raw.getOrDefault("forbiddenFacts", List.of()),
                (List<String>) raw.getOrDefault("requiredSections", List.of()),
                Boolean.TRUE.equals(raw.get("ragRequired")),
                raw.get("maxLoopSteps") instanceof Number number ? number.intValue() : 3,
                (List<String>) raw.getOrDefault("allowedLoopActions", List.of())
            );
        }

        private static List<String> safeList(List<String> values) {
            return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        }
    }

    public record Evaluation(
        String sampleId,
        String evaluatorVersion,
        Map<String, Double> scores,
        double overallScore,
        List<String> violations
    ) {
        public Evaluation {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public boolean passed() {
            return violations.isEmpty();
        }
    }
}
