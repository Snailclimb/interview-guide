package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 面试教练 Agent 的只读上下文编排。
 *
 * <p>负责把已有的面试报告、Skill references、长期记忆和知识库检索结果整理成 Agent Loop
 * 所需的上下文；不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class AgentContextService {
    private static final int MAX_REPORT_CHARS = 14_000;
    private static final int MAX_RAG_CONTEXT_CHARS = 8_000;
    private static final String RAG_TRUNCATION_NOTICE = "[该来源片段已截断]";
    private static final int MAX_RETRIEVAL_QUERY_CHARS = 1_800;
    private static final int MAX_RETRIEVAL_HISTORY_MESSAGES = 6;
    private static final int MAX_RETRIEVAL_HISTORY_CHARS = 2_400;
    private static final int MAX_WEAK_ANSWERS = 4;
    private static final int WEAK_SCORE_THRESHOLD = 70;

    private final InterviewHistoryService interviewHistoryService;
    private final InterviewSkillService interviewSkillService;
    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final PromptSanitizer promptSanitizer;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;

    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper
    ) {
        this(
            interviewHistoryService, interviewSkillService, knowledgeBaseQueryService,
            promptSanitizer, objectMapper, null
        );
    }

    @Autowired
    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper,
        AgentMemoryService agentMemoryService
    ) {
        this.interviewHistoryService = interviewHistoryService;
        this.interviewSkillService = interviewSkillService;
        this.knowledgeBaseQueryService = knowledgeBaseQueryService;
        this.promptSanitizer = promptSanitizer;
        this.objectMapper = objectMapper;
        this.agentMemoryService = agentMemoryService;
    }

    public AgentContext build(
        String sessionId,
        AgentMessageRequest request,
        List<AgentHistoryMessage> effectiveHistory
    ) {
        InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(sessionId);
        String report = serializeReport(detail);
        List<AgentSource> sources = new ArrayList<>();

        String skillReference = loadSkillReference(detail, sources);
        String retrievalQuery = buildRetrievalQuery(detail, request);
        String ragContext = loadRagContext(detail, retrievalQuery, sources, buildRetrievalHistory(effectiveHistory));
        AgentMemorySnapshot memory = loadAndUpdateMemory(detail, request);

        return new AgentContext(
            report,
            detail.skillId(),
            skillReference,
            ragContext,
            retrievalQuery,
            List.copyOf(sources),
            memory.toPrompt(detail.skillId()),
            extractWeaknesses(detail)
        );
    }

    private AgentMemorySnapshot loadAndUpdateMemory(InterviewDetailDTO detail, AgentMessageRequest request) {
        if (agentMemoryService == null) {
            return AgentMemorySnapshot.empty(request.memoryKey());
        }
        return agentMemoryService.remember(
            request.memoryKey(),
            detail.skillId(),
            extractWeaknesses(detail),
            request.goal()
        );
    }

    private List<String> extractWeaknesses(InterviewDetailDTO detail) {
        List<String> weaknesses = new ArrayList<>();
        if (detail.improvements() != null) {
            detail.improvements().stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(MAX_WEAK_ANSWERS)
                .forEach(item -> weaknesses.add(promptSanitizer.sanitize(item)));
        }
        if (detail.answers() != null) {
            detail.answers().stream()
                .filter(answer -> answer.score() != null && answer.score() < WEAK_SCORE_THRESHOLD)
                .limit(MAX_WEAK_ANSWERS)
                .forEach(answer -> weaknesses.add(
                    promptSanitizer.sanitize(answer.category() + "：" + answer.question())
                ));
        }
        return weaknesses.stream().filter(item -> item != null && !item.isBlank()).distinct().limit(8).toList();
    }

    private String loadSkillReference(InterviewDetailDTO detail, List<AgentSource> sources) {
        if (detail.skillId() == null || detail.skillId().isBlank()) {
            return "暂无匹配的 Skill references";
        }
        try {
            String reference = interviewSkillService.buildEvaluationReferenceSectionSafe(detail.skillId());
            if (reference != null && !reference.isBlank() && !"未配置 references。".equals(reference)) {
                sources.add(new AgentSource("skill", "Skill: " + detail.skillId(), null, "skill-reference"));
                return truncate(reference, 8_000);
            }
        } catch (Exception error) {
            log.warn("Agent 加载 Skill references 失败: skillId={}, error={}", detail.skillId(),
                error.getClass().getSimpleName());
        }
        return "暂无匹配的 Skill references";
    }

    private String loadRagContext(
        InterviewDetailDTO detail,
        String retrievalQuery,
        List<AgentSource> sources,
        List<Message> history
    ) {
        if (detail.knowledgeBaseId() == null || retrievalQuery.isBlank()) {
            return "暂无知识库检索上下文";
        }

        try {
            RagQueryExecution execution = knowledgeBaseQueryService.retrieveOnly(
                List.of(detail.knowledgeBaseId()), retrievalQuery, history
            );
            if (execution.retrievedDocs().isEmpty()) {
                return "本次未检索到达到阈值的知识库片段";
            }

            StringBuilder context = new StringBuilder();
            for (int index = 0; index < execution.retrievedDocs().size(); index++) {
                RagQueryExecution.RetrievedDoc document = execution.retrievedDocs().get(index);
                String reference = "R" + (index + 1);
                String title = describe(document.metadata());
                String text = document.text() == null ? "" : document.text();
                String prefix = "[" + reference + "] 来源：" + title + '\n';
                int remaining = MAX_RAG_CONTEXT_CHARS - context.length();
                if (remaining <= prefix.length()) {
                    break;
                }
                int textBudget = remaining - prefix.length() - 2;
                boolean needsTruncation = text.length() > textBudget;
                if (needsTruncation) {
                    textBudget -= RAG_TRUNCATION_NOTICE.length() + 1;
                }
                String includedText = text.length() <= textBudget
                    ? text
                    : text.substring(0, Math.max(0, textBudget));
                if (includedText.isBlank()) {
                    break;
                }
                sources.add(new AgentSource("rag", title, document.score(), reference));
                context.append(prefix).append(includedText).append("\n\n");
                if (includedText.length() < text.length()) {
                    context.append(RAG_TRUNCATION_NOTICE).append('\n');
                    break;
                }
            }
            return truncate(context.toString(), MAX_RAG_CONTEXT_CHARS);
        } catch (Exception error) {
            log.warn("Agent RAG 检索失败，降级为面试报告上下文: sessionId={}, kbId={}, error={}",
                detail.sessionId(), detail.knowledgeBaseId(), error.getClass().getSimpleName());
            return "知识库检索暂时不可用，本次回答不要声称使用了知识库依据";
        }
    }

    /**
     * RAG 查询改写只需要最近几轮对话，不复用完整 Agent 历史，避免检索请求膨胀。
     * 每条消息先净化，再按总字符预算从最近消息向前保留。
     */
    private List<Message> buildRetrievalHistory(List<AgentHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        int remaining = MAX_RETRIEVAL_HISTORY_CHARS;
        int start = Math.max(0, history.size() - MAX_RETRIEVAL_HISTORY_MESSAGES);
        for (int index = history.size() - 1; index >= start && remaining > 0; index--) {
            AgentHistoryMessage item = history.get(index);
            if (item == null || item.role() == null || item.content() == null) {
                continue;
            }
            String content = promptSanitizer.sanitize(item.content().trim());
            if (content.isBlank()) {
                continue;
            }
            int budget = Math.min(remaining, content.length());
            String compact = content.substring(Math.max(0, content.length() - budget));
            Message message = "assistant".equals(item.role())
                ? new AssistantMessage(compact)
                : new UserMessage(compact);
            result.add(message);
            remaining -= compact.length();
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private String buildRetrievalQuery(InterviewDetailDTO detail, AgentMessageRequest request) {
        StringBuilder query = new StringBuilder();
        query.append("目标：").append(promptSanitizer.sanitize(request.goal())).append('\n');
        query.append("用户问题：").append(promptSanitizer.sanitize(request.message())).append('\n');

        if (detail.improvements() != null && !detail.improvements().isEmpty()) {
            query.append("报告改进项：");
            detail.improvements().stream()
                .limit(MAX_WEAK_ANSWERS)
                .forEach(item -> query.append(promptSanitizer.sanitize(item)).append("；"));
            query.append('\n');
        }

        if (detail.answers() != null) {
            detail.answers().stream()
                .filter(answer -> answer.score() != null && answer.score() < WEAK_SCORE_THRESHOLD)
                .limit(MAX_WEAK_ANSWERS)
                .forEach(answer -> query.append("薄弱回答（")
                    .append(answer.category()).append("，")
                    .append(answer.score()).append("分）：")
                    .append(promptSanitizer.sanitize(answer.question())).append('；'));
        }
        return truncate(query.toString(), MAX_RETRIEVAL_QUERY_CHARS);
    }

    private String serializeReport(InterviewDetailDTO detail) {
        try {
            return truncate(objectMapper.writeValueAsString(detail), MAX_REPORT_CHARS);
        } catch (JacksonException error) {
            throw new IllegalStateException("面试报告整理失败", error);
        }
    }

    private String describe(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "知识库片段";
        }
        String source = text(metadata.get("source_name"));
        if (source.isBlank()) {
            source = text(metadata.get("source_filename"));
        }
        return source.isBlank() ? "知识库片段" : source;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n[内容已截断]";
    }

    public record AgentContext(
        String interviewReport,
        String skillId,
        String skillReference,
        String ragContext,
        String retrievalQuery,
        List<AgentSource> sources,
        String longTermMemory,
        List<String> weaknesses
    ) {
        public AgentContext(
            String interviewReport,
            String skillId,
            String skillReference,
            String ragContext,
            String retrievalQuery,
            List<AgentSource> sources
        ) {
            this(interviewReport, skillId, skillReference, ragContext, retrievalQuery, sources,
                "暂无长期记忆", List.of());
        }

        public AgentContext {
            longTermMemory = longTermMemory == null ? "暂无长期记忆" : longTermMemory;
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
