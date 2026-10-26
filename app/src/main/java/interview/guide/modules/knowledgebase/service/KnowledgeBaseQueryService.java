package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.knowledgebase.metrics.RagMetrics;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final RagMetrics ragMetrics;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final boolean mergeOriginalQuery;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeBaseAccessService accessService;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            RagMetrics ragMetrics,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.ragMetrics = ragMetrics;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.mergeOriginalQuery = queryProperties.getSearch().isMergeOriginalQuery();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getPlainChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        long totalStart = System.nanoTime();
        String normalized = normalizeQuestion(question);
        log.info("收到知识库提问: kbIds={}, questionLength={}", knowledgeBaseIds, normalized.length());
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            ragMetrics.recordRequest("sync", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return NO_RESULT_RESPONSE;
        }
        requireReadableKnowledgeBases(knowledgeBaseIds);

        QueryContext queryContext = null;
        long retrievalNanos = 0;
        try {
            countService.updateQuestionCounts(knowledgeBaseIds);

            queryContext = buildQueryContext(question, List.of());
            long retrievalStart = System.nanoTime();
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            retrievalNanos = System.nanoTime() - retrievalStart;

            if (!hasEffectiveHit(relevantDocs)) {
                ragMetrics.recordRequest("sync", "reject");
                ragMetrics.recordRefusal("no_hit");
                ragMetrics.recordStageDuration("rewrite", "reject",
                    queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "reject", retrievalNanos);
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
                return NO_RESULT_RESPONSE;
            }

            String context = RagSources.context(relevantDocs);
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            long generationStart = System.nanoTime();
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);
            long generationNanos = System.nanoTime() - generationStart;

            boolean rejected = NO_RESULT_RESPONSE.equals(answer);
            ragMetrics.recordRequest("sync", rejected ? "reject" : "success");
            if (rejected) {
                ragMetrics.recordRefusal("no_hit");
            }
            ragMetrics.recordStageDuration("rewrite", rejected ? "reject" : "success",
                queryContext.rewriteDurationMs() * 1_000_000L);
            ragMetrics.recordStageDuration("retrieve", rejected ? "reject" : "success", retrievalNanos);
            ragMetrics.recordStageDuration("generate", rejected ? "reject" : "success", generationNanos);
            ragMetrics.recordStageDuration("total", rejected ? "reject" : "success",
                System.nanoTime() - totalStart);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return rejected ? answer : answer + RagSources.footer(relevantDocs);

        } catch (Exception e) {
            log.error("知识库问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            ragMetrics.recordRequest("sync", "error");
            if (queryContext != null) {
                ragMetrics.recordStageDuration("rewrite", "error",
                    queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "error", retrievalNanos);
            }
            ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "知识库查询失败，请稍后重试");
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return answerQuestionStream(knowledgeBaseIds, question, history, null);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文），可选地输出完整执行轨迹。
     * 轨迹收集器仅供测评与可观测性使用，不影响业务行为。
     *
     * @param trace 执行轨迹收集器（可为 null，为 null 时行为与无收集器入口完全一致）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history,
                                             java.util.function.Consumer<RagQueryExecution> trace) {
        long totalStart = System.nanoTime();
        String normalized = normalizeQuestion(question);
        log.info("收到知识库流式提问: kbIds={}, questionLength={}, historySize={}", knowledgeBaseIds,
                normalized.length(), history != null ? history.size() : 0);
        // 一次请求只允许记录一次结果：error 路径（含 onErrorResume 吞异常后的 Flux.just）先落，complete 检查后跳过
        java.util.concurrent.atomic.AtomicBoolean resultRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            ragMetrics.recordRequest("stream", "reject");
            ragMetrics.recordRefusal("invalid_request");
            ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
            return Flux.just(NO_RESULT_RESPONSE);
        }
        requireReadableKnowledgeBases(knowledgeBaseIds);

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            long rewriteStart = System.nanoTime();
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<String> attemptedQueries = new ArrayList<>();
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, attemptedQueries);
            long rewriteAndRetrievalMs = (System.nanoTime() - rewriteStart) / 1_000_000;

            if (!hasEffectiveHit(relevantDocs)) {
                emitTrace(trace, question, queryContext, attemptedQueries, relevantDocs,
                    rewriteAndRetrievalMs, 0, NO_RESULT_RESPONSE, "NO_RESULT");
                ragMetrics.recordRequest("stream", "reject");
                ragMetrics.recordRefusal("no_hit");
                ragMetrics.recordStageDuration("rewrite", "reject", queryContext.rewriteDurationMs() * 1_000_000L);
                ragMetrics.recordStageDuration("retrieve", "reject",
                    Math.max(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs()) * 1_000_000L);
                ragMetrics.recordStageDuration("total", "reject", System.nanoTime() - totalStart);
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = RagSources.context(relevantDocs);

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            long generationStart = System.nanoTime();
            StringBuilder collectedAnswer = new StringBuilder();
            return normalizeStreamOutput(responseFlux)
                .doOnNext(collectedAnswer::append)
                .concatWith(Flux.defer(() -> {
                    if (NO_RESULT_RESPONSE.equals(collectedAnswer.toString())) {
                        return Flux.empty();
                    }
                    String footer = RagSources.footer(relevantDocs);
                    collectedAnswer.append(footer);
                    return Flux.just(footer);
                }))
                .doOnComplete(() -> {
                    // error 已记录（onErrorResume 转 Flux.just 后仍会走到 complete）时跳过
                    if (!resultRecorded.compareAndSet(false, true)) {
                        return;
                    }
                    long generationNanos = System.nanoTime() - generationStart;
                    boolean rejected = NO_RESULT_RESPONSE.equals(collectedAnswer.toString());
                    String result = rejected ? "reject" : "success";
                    ragMetrics.recordRequest("stream", result);
                    if (rejected) {
                        ragMetrics.recordRefusal("no_hit");
                    }
                    ragMetrics.recordStageDuration("rewrite", result, queryContext.rewriteDurationMs() * 1_000_000L);
                    ragMetrics.recordStageDuration("retrieve", result,
                        Math.max(0, rewriteAndRetrievalMs - queryContext.rewriteDurationMs()) * 1_000_000L);
                    ragMetrics.recordStageDuration("generate", result, generationNanos);
                    ragMetrics.recordStageDuration("total", result, System.nanoTime() - totalStart);
                    emitTrace(trace, question, queryContext, attemptedQueries, relevantDocs,
                        rewriteAndRetrievalMs, generationNanos / 1_000_000, collectedAnswer.toString(),
                        rejected ? "NO_RESULT" : "ANSWERED");
                    log.info("流式输出完成: kbIds={}", knowledgeBaseIds);
                })
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds,
                        ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "error");
                        ragMetrics.recordStageDuration("generate", "error", System.nanoTime() - generationStart);
                        ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
                    }
                    emitTrace(trace, question, queryContext, attemptedQueries, List.of(),
                        rewriteAndRetrievalMs, (System.nanoTime() - generationStart) / 1_000_000,
                        "【错误】知识库查询失败", "ERROR");
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                })
                .doOnCancel(() -> {
                    if (resultRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordRequest("stream", "cancel");
                        ragMetrics.recordStageDuration("generate", "cancel", System.nanoTime() - generationStart);
                        ragMetrics.recordStageDuration("total", "cancel", System.nanoTime() - totalStart);
                    }
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            // 外层 catch 返回的错误文案同样记为 error，不是 success
            if (resultRecorded.compareAndSet(false, true)) {
                ragMetrics.recordRequest("stream", "error");
                ragMetrics.recordStageDuration("total", "error", System.nanoTime() - totalStart);
            }
            emitTrace(trace, normalizeQuestion(question), null, List.of(),
                List.of(), 0, 0, "【错误】知识库查询失败", "ERROR");
            return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 输出执行轨迹（收集器为 null 时直接返回）。
     */
    private void emitTrace(java.util.function.Consumer<RagQueryExecution> trace,
                           String originalQuestion, QueryContext queryContext,
                           List<String> attemptedQueries, List<Document> chosenDocs,
                           long rewriteAndRetrievalMs, long generationMs,
                           String answer, String outcome) {
        if (trace == null) {
            return;
        }
        List<RagQueryExecution.RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < chosenDocs.size(); i++) {
            Document doc = chosenDocs.get(i);
            docs.add(new RagQueryExecution.RetrievedDoc(
                i + 1, doc.getText(), extractScore(doc), doc.getMetadata()));
        }
        SearchParams params = queryContext != null ? queryContext.searchParams()
            : new SearchParams(topkLong, minScoreDefault);
        long rewriteMs = queryContext != null ? queryContext.rewriteDurationMs() : 0;
        trace.accept(new RagQueryExecution(
            normalizeQuestion(originalQuestion),
            queryContext != null && !queryContext.candidateQueries().isEmpty()
                ? queryContext.candidateQueries().getFirst() : normalizeQuestion(originalQuestion),
            attemptedQueries,
            params.topK(),
            params.minScore(),
            List.copyOf(docs),
            rewriteMs,
            Math.max(0, rewriteAndRetrievalMs - rewriteMs),
            generationMs,
            answer,
            outcome));
    }

    private Double extractScore(Document doc) {
        Double score = doc.getScore();
        if (score != null) {
            return score;
        }
        Object distance = doc.getMetadata().get("distance");
        return distance != null && distance instanceof Number number ? number.doubleValue() : null;
    }


    /**
     * 仅执行检索链路（改写 + 候选检索），不做 LLM 生成。
     * 供 RAG 测评复用生产检索逻辑，避免在测评代码中复制改写与分档规则。
     */
    public RagQueryExecution retrieveOnly(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        String normalized = normalizeQuestion(question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalized.isBlank()) {
            return new RagQueryExecution(normalized, normalized, List.of(normalized),
                topkLong, minScoreDefault, List.of(), 0, 0, 0, NO_RESULT_RESPONSE, "NO_RESULT");
        }
        requireReadableKnowledgeBases(knowledgeBaseIds);
        long start = System.nanoTime();
        List<Message> effectiveHistory = sanitizeHistory(history);
        QueryContext queryContext = buildQueryContext(question, effectiveHistory);
        List<String> attemptedQueries = new ArrayList<>();
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, attemptedQueries);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        boolean hit = hasEffectiveHit(relevantDocs);
        String stageResult = hit ? "success" : "reject";
        ragMetrics.recordStageDuration("rewrite", stageResult, queryContext.rewriteDurationMs() * 1_000_000L);
        ragMetrics.recordStageDuration("retrieve", stageResult,
            Math.max(0, elapsedMs - queryContext.rewriteDurationMs()) * 1_000_000L);
        return new RagQueryExecution(
            normalized,
            queryContext.candidateQueries().getFirst(),
            List.copyOf(attemptedQueries),
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore(),
            relevantDocs.stream()
                .map(doc -> new RagQueryExecution.RetrievedDoc(
                    relevantDocs.indexOf(doc) + 1, doc.getText(), extractScore(doc), doc.getMetadata()))
                .toList(),
            queryContext.rewriteDurationMs(),
            Math.max(0, elapsedMs - queryContext.rewriteDurationMs()),
            0,
            null,
            hit ? "RETRIEVED" : "NO_RESULT");
    }

    private void requireReadableKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (accessService != null) {
            accessService.requireReadableIds(knowledgeBaseIds);
        }
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        long rewriteStart = System.nanoTime();
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        long rewriteDurationMs = (System.nanoTime() - rewriteStart) / 1_000_000;
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams, rewriteDurationMs);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索（attemptedQueries 非空时记录实际尝试过的候选，第一个命中后即返回）
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        return retrieveRelevantDocs(queryContext, knowledgeBaseIds, null);
    }

    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds,
                                                List<String> attemptedQueries) {
        List<String> candidates = queryContext.candidateQueries();
        if (mergeOriginalQuery && candidates.size() > 1
                && !candidates.get(0).equals(candidates.get(1))) {
            return retrieveAndMerge(queryContext, knowledgeBaseIds, attemptedQueries);
        }
        for (int i = 0; i < candidates.size(); i++) {
            String candidateQuery = candidates.get(i);
            if (candidateQuery.isBlank()) {
                continue;
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery);
            }
            long startNanos = System.nanoTime();
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("RAG 检索完成: kbCount={}, questionLength={}, queryVariant={}, hits={}, durationMs={}",
                knowledgeBaseIds.size(), candidateQuery.length(),
                i == 0 ? "rewritten" : "original", docs.size(), durationMs);
            if (hasEffectiveHit(docs)) {
                ragMetrics.recordRetrievalHits("single", docs.size());
                return docs;
            }
        }
        return List.of();
    }

    /**
     * 双路召回融合：改写 Query 与原始 Query 各自按相同 Top K/阈值检索一次，
     * 按 Document ID 去重保留高分，null 分数排后、同分保持改写路优先的稳定顺序，
     * 按分数降序截取最终 Top K。两路均来自同一向量库与相似度定义，第一版用最大分数融合。
     */
    private List<Document> retrieveAndMerge(QueryContext queryContext, List<Long> knowledgeBaseIds,
                                            List<String> attemptedQueries) {
        Map<String, Document> merged = new LinkedHashMap<>();
        int topK = queryContext.searchParams().topK();
        int rewrittenHits = 0;
        int originalHits = 0;
        for (int i = 0; i < 2; i++) {
            String candidateQuery = queryContext.candidateQueries().get(i);
            if (candidateQuery.isBlank()) {
                continue;
            }
            if (attemptedQueries != null) {
                attemptedQueries.add(candidateQuery);
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery, knowledgeBaseIds, topK, queryContext.searchParams().minScore());
            if (i == 0) {
                rewrittenHits = docs.size();
            } else {
                originalHits = docs.size();
            }
            for (Document doc : docs) {
                merged.merge(doc.getId(), doc, KnowledgeBaseQueryService::higherScore);
            }
        }
        List<Document> result = merged.values().stream()
            .sorted(KnowledgeBaseQueryService::scoreDescendingNullsLast)
            .limit(topK)
            .collect(Collectors.toList());
        log.info("RAG 双路融合完成: kbCount={}, rewrittenHits={}, originalHits={}, dedupedHits={}, finalHits={}, topK={}",
            knowledgeBaseIds.size(), rewrittenHits, originalHits, merged.size(), result.size(), topK);
        ragMetrics.recordRetrievalHits("rewritten", rewrittenHits);
        ragMetrics.recordRetrievalHits("original", originalHits);
        ragMetrics.recordRetrievalHits("merged", result.size());
        return result;
    }

    private static Document higherScore(Document existing, Document incoming) {
        Double existingScore = existing.getScore();
        Double incomingScore = incoming.getScore();
        if (incomingScore == null) {
            return existing;
        }
        if (existingScore == null || incomingScore > existingScore) {
            return incoming;
        }
        return existing;
    }

    /**
     * 分数降序且 null 分数排最后；分数相同时保持"改写 Query 结果优先"的稳定顺序（稳定排序保证）。
     */
    private static int scoreDescendingNullsLast(Document a, Document b) {
        Double scoreA = a.getScore();
        Double scoreB = b.getScore();
        if (scoreA == null && scoreB == null) {
            return 0;
        }
        if (scoreA == null) {
            return 1;
        }
        if (scoreB == null) {
            return -1;
        }
        return Double.compare(scoreB, scoreA);
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled) {
            ragMetrics.recordRewriteFallback("disabled");
            return question;
        }
        if (question.isBlank()) {
            ragMetrics.recordRewriteFallback("blank");
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            if (normalized.equals(question)) {
                ragMetrics.recordRewriteFallback("unchanged");
            }
            log.info("Query rewrite 完成: originLength={}, rewrittenLength={}, changed={}, historySize={}",
                question.length(), normalized.length(), !normalized.equals(question), history.size());
            return normalized;
        } catch (Exception e) {
            ragMetrics.recordRewriteFallback("error");
            log.warn("Query rewrite 失败，使用原问题继续检索: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams,
                                long rewriteDurationMs) {
    }
}
