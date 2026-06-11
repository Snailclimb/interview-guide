package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文字面试和语音面试共用的评估服务。
 * <p>
 * 评估采用<b>分批评估 → 二次汇总</b>的两阶段设计：
 * <ol>
 *   <li><b>分批评估</b>：将问答记录按 {@code batchSize} 分组，每组调用 LLM 生成结构化评估。分批是因为 LLM 的
 *      上下文窗口有限，一次塞入数十题会导致注意力分散、早期信息被遗忘（lost-in-the-middle），降低评分准确性；</li>
 *   <li><b>二次汇总</b>：将各批次的评估结果（按题目维度）和批次级总评（按批次维度）一并喂给 LLM 重新整理，
 *      输出统一的综合评价。避免"各批次各说各话"导致的融合感差。</li>
 * </ol>
 * 每次 LLM 调用均使用 {@link StructuredOutputInvoker} 自动重试 + 结构化输出，
 * 确保返回格式稳定可解析。任一批次失败不影响其他批次——有降级兜底。
 * <p>
 * 之所以放在 {@code common.evaluation} 而非某个业务模块，是因为 {@code interview} 和
 * {@code voiceinterview} 两个模块都需要评估，避免重复实现。
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    // 参考基线文本最长 6000 字符（约 3000~4000 tokens），与简历截断策略配合，
    // 确保两路上下文合计不超过单批次 LLM 输入的上限
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final ResourceLoader resourceLoader;

    // 以下四个内部 record 仅在本服务内部传递数据，不暴露到外部 API：
    // BatchReportDTO 对应 LLM 单批次输出的结构化 schema
    // QuestionEvalDTO 是单题评估的扁平载体，按 questionIndex 对齐
    // BatchResult 封装某批次的索引范围+报告，合并阶段按索引定位
    // SummaryDTO 是二次汇总 LLM 调用的输出 schema，只包含汇总级别的字段
    private record BatchReportDTO(
        // 本批次整体评分（0-100）
        int overallScore,
        // 本批次整体评语
        String overallFeedback,
        // 候选人表现优点列表
        List<String> strengths,
        // 候选人待改进点列表
        List<String> improvements,
        // 本批次每道题的逐题评估结果
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    private record QuestionEvalDTO(
        // 题目在完整问答列表中的全局索引（0-based），用于跨批次合并时对齐
        int questionIndex,
        // 单题评分（0-100）
        int score,
        // 单题评估反馈
        String feedback,
        // 参考答案
        String referenceAnswer,
        // 参考答案的关键要点
        List<String> keyPoints
    ) {}

    private record BatchResult(
        // 本批次在完整问答列表中的起始索引（含）
        int startIndex,
        // 本批次在完整问答列表中的结束索引（不含）
        int endIndex,
        // 本批次的评估报告，评估失败时为 null
        BatchReportDTO report
    ) {}

    private record SummaryDTO(
        // 二次汇总后的综合评语，为空时降级使用批次聚合结果
        String overallFeedback,
        // 汇总后的优点列表
        List<String> strengths,
        // 汇总后的改进点列表
        List<String> improvements
    ) {}

    /**
     * @param structuredOutputInvoker 结构化输出重试封装——两次 LLM 调用（批次评估 + 二次汇总）都经过它，
     *                                确保反序列化失败时自动重试，不因一次格式异常中断整个评估流程
     * @param resourceLoader          从 classpath 加载 Prompt 模板文件（.st 格式），
     *                                路径由配置 {@code app.evaluation.prompts.*} 指定
     * @param evaluationProperties    批大小可调：调小降低每次 LLM 调用丢给模型的压力但总调用次数增加，
     *                                调大减少调用次数但增加上下文丢失风险——默认值需按实际模型上下文长度调整
     */
    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        // 批大小 <= 0 则退化为一次评一题——安全性兜底，撞墙场景（配置错误）下不至于抛出异常
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    /**
     * 评估入口（无参考基线版本）。
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    /**
     * 评估入口（含参考基线）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>截断简历和参考基线至合理长度，避免单次 LLM 调用超过上下文上限（截断在前，分批在后）；</li>
     *   <li>按 {@link #evaluationBatchSize} 分批调用 LLM，每批输出结构化的评分子集；</li>
     *   <li>合并所有批次的评估结果为扁平列表（漏评的题兜底为 0 分）；</li>
     *   <li>将合并结果 + 原始问答喂给 LLM 做二次汇总，生成统一的综合评价（这一步骤才是最终呈现在报告中的总评）；</li>
     *   <li>二次汇总失败时降级使用批次级合并结果，确保评估报告不会为空。</li>
     * </ol>
     *
     * @param referenceContext 参考基线（期望的回答标准），传 null 或空串时 LLM 自行评判。
     *                         仅在 {@code voiceinterview} 模块的预评估场景中提供，
     *                         {@code interview} 模块不用——这是为什么提供两个重载，而非统一的必选参数
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        // 截断至 3000 字符（约 1500~2000 tokens）：保证简历上下文 + 参考基线 + N 道问答的总和
        // 不超过绝大多数 embedding 模型和 chat 模型的输入上限。截断在批次处理之前做，
        // 避免每批都重复截断同一份简历
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }

        // 分批评估
        List<BatchResult> batchResults = evaluateInBatches(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        // 合并批次结果
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // 二次汇总
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        return buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 顺序执行分批评估而非并行，原因有二：
     * <ol>
     *   <li>保证评估结果的确定性顺序——题号递增，便于后续按索引合并；</li>
     *   <li>避免同一 LLM Provider 的并发限流或资源争抢（常见于 Serverless 部署）。</li>
     * </ol>
     * 某个批次失败（返回 null）不影响后续批次继续评估，最终合并逻辑能按题位填补兜底分数。
     */
    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    /**
     * 单批次评估：组装 Prompt → 调用 LLM → 结构化输出 → 返回 DTO。
     * 失败时返回 null 而非抛异常，因为：
     * <ol>
     *   <li>批间解耦——某一题评估失败不应牵连其他题目；</li>
     *   <li>合并逻辑已具备兜底能力（0 分 + 占位文本），无需上层额外处理。</li>
     * </ol>
     * 格式指令（{@link BeanOutputConverter#getFormat}）追加到 system prompt 末尾而非嵌在 user prompt 中，
     * 因为 AI 模型通常将 system prompt 的内容视为更强的约束指令格式，结构化输出命中率更高。
     */
    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size(), e.getMessage(), e);
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n",
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    /**
     * 将各批次的评估结果按原始题序合并为扁平列表。
     * LLM 可能漏评某些题目（返回数量少于预期），或整批评估失败（report == null），
     * 此时用 0 分 + 占位文本填充缺失位，保证返回列表长度 = 题目总数，后续 buildReport 无需检查越界。
     */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    merged.add(new QuestionEvalDTO(
                        result.startIndex() + i, 0,
                        "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()
                    ));
                }
            }
        }
        return merged;
    }

    /**
     * 合并各批次的总体评语。批次间用双换行分隔，保留 LLM 对各批次独立的语段边界，
     * 比用句号/逗号拼接更易于阅读（且二次汇总 LLM 也能据此识别分段）。
     */
    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(BatchReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    /**
     * 合并各批次的优点/改进点列表。
     * 使用 {@link LinkedHashSet} 在去重的同时保留原始出现顺序——同一优点被多个批次提及说明重要性高，
     * 保留在前更能反映 LLM 的关注焦点。
     * 限制 8 条：评估报告 UI 通常在折叠区域展示列表，条目过多会过度撑大报告篇幅。
     */
    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    /**
     * 二次汇总：将各批次的分散评估结果送入 LLM 做一次综合提炼。
     * <p>
     * 这一步之所以必要，是因为分批评估中每批 LLM 只看到自己那几道题的问答，无法产出全局视角的总评。
     * 二次汇总的 prompt 包含三类信息：
     * <ol>
     *   <li>分维度的平均分（categorySummary）——让 LLM 看到强弱势领域；</li>
     *   <li>每道题的简要评估摘要（questionHighlights）——提供逐题粒度但不暴露完整原文（已截断），节省 tokens；</li>
     *   <li>分批聚合的 fallback 总评/优点/改进点——LLM 即使拒绝输出也可降级使用。</li>
     * </ol>
     * 降级设计：任一字段为空时退守 fallback 值；调用本身抛异常时使用完整的 fallback SummaryDTO。
     * 这就是统一评估"最少也有基础结果"的最后兜底。
     */
    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    /**
     * 合并 LLM 输出与 fallback 列表，优先使用 LLM 输出但确保不为空。
     * null 与 empty 的区别很重要：
     * <ul>
     *   <li>primary == null → LLM 未生成该字段（结构化输出字段缺失），退到 fallback；</li>
     *   <li>primary.isEmpty() → LLM 生成了一个空数组，视为"没有值得列出的项"，同样退到 fallback；</li>
     *   <li>primary 有值但全部是空白字符串 → 清理后变空，退到 fallback。</li>
     * </ul>
     */
    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    /**
     * 将内部评估数据组装为 {@link EvaluationReport} 输出对象。
     * <p>
     * 关键安全设计：
     * <ul>
     *   <li>未回答的题强制 0 分，即使 LLM 给出了评分（防止 AI 对"未回答"给出同情分）；</li>
     *   <li>evaluations 长度不足时（合并阶段已尽力填充，仍差少数），靠 {@code i < evalSize} 边界保护，取不到时视作未评分；</li>
     *   <li>overallScore 的计算仅计入有回答的题目，避免未答题拉低总分。</li>
     * </ul>
     */
    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        // 先统计实际回答的题数，用于后续 overallScore 的除数——不应将未答题计入分母
        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback() : "该题未成功生成评估反馈。";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        int overallScore = answeredCount == 0 ? 0
            : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        return new EvaluationReport(
            sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }

    /**
     * 生成分维度的平均分文本，作为二次汇总 prompt 的输入素材。
     * 之所以在此重新统计而非复用 {@link #buildReport} 中已完成的 categoryScores，
     * 是因为这里的输出是供 LLM 读的纯文本，而非最终报告中的结构化数据——两者的消费者不同，
     * 不共享同一份中间状态可以减少方法间耦合。
     */
    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    /**
     * 生成每道题的简要评估摘要（问题和反馈各截断至 50/80 字符），作为二次汇总 prompt 的输入素材。
     * 原始问答文本长度可达数千字符，直接塞入将膨胀 prompt 导致 token 消耗翻倍且分散 LLM 注意力。
     * 截断 + 限制 20 条是平衡信息完整度与 token 成本的经验值。
     */
    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}
