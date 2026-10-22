package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 面试教练 Agent 的只读上下文编排。
 *
 * <p>负责把已有的面试报告、Skill references 和知识库检索结果整理成一次模型调用所需的上下文。
 * 不保存数据，也不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class AgentContextService {
    private static final int MAX_REPORT_CHARS = 14_000;
    private static final int MAX_RAG_CONTEXT_CHARS = 8_000;
    private static final int MAX_RETRIEVAL_QUERY_CHARS = 1_800;
    private static final int MAX_WEAK_ANSWERS = 4;
    private static final int WEAK_SCORE_THRESHOLD = 70;

    private final InterviewHistoryService interviewHistoryService;
    private final InterviewSkillService interviewSkillService;
    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final PromptSanitizer promptSanitizer;
    private final ObjectMapper objectMapper;

    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper
    ) {
        this.interviewHistoryService = interviewHistoryService;
        this.interviewSkillService = interviewSkillService;
        this.knowledgeBaseQueryService = knowledgeBaseQueryService;
        this.promptSanitizer = promptSanitizer;
        this.objectMapper = objectMapper;
    }

    public AgentContext build(String sessionId, AgentMessageRequest request) {
        InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(sessionId);
        String report = serializeReport(detail);
        List<AgentSource> sources = new ArrayList<>();

        String skillReference = loadSkillReference(detail, sources);
        String retrievalQuery = buildRetrievalQuery(detail, request);
        String ragContext = loadRagContext(detail, retrievalQuery, sources);

        return new AgentContext(
            report,
            detail.skillId(),
            skillReference,
            ragContext,
            retrievalQuery,
            List.copyOf(sources)
        );
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
        List<AgentSource> sources
    ) {
        if (detail.knowledgeBaseId() == null || retrievalQuery.isBlank()) {
            return "暂无知识库检索上下文";
        }

        try {
            RagQueryExecution execution = knowledgeBaseQueryService.retrieveOnly(
                List.of(detail.knowledgeBaseId()), retrievalQuery, List.of()
            );
            if (execution.retrievedDocs().isEmpty()) {
                return "本次未检索到达到阈值的知识库片段";
            }

            StringBuilder context = new StringBuilder();
            for (int index = 0; index < execution.retrievedDocs().size(); index++) {
                RagQueryExecution.RetrievedDoc document = execution.retrievedDocs().get(index);
                String reference = "R" + (index + 1);
                String title = describe(document.metadata());
                sources.add(new AgentSource("rag", title, document.score(), reference));
                context.append("[").append(reference).append("] 来源：").append(title).append('\n')
                    .append(document.text()).append("\n\n");
                if (context.length() >= MAX_RAG_CONTEXT_CHARS) {
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
        List<AgentSource> sources
    ) {
    }
}
