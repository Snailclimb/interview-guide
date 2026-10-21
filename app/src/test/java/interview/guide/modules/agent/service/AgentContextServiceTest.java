package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentContextServiceTest {

    @Test
    void shouldLoadSkillReferenceForNormalInterview() throws Exception {
        InterviewHistoryService history = mock(InterviewHistoryService.class);
        InterviewSkillService skills = mock(InterviewSkillService.class);
        KnowledgeBaseQueryService rag = mock(KnowledgeBaseQueryService.class);
        PromptSanitizer sanitizer = mock(PromptSanitizer.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        InterviewDetailDTO detail = detail("java-backend", null);

        when(history.getInterviewDetail("session-1")).thenReturn(detail);
        when(objectMapper.writeValueAsString(detail)).thenReturn("{report}");
        when(sanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skills.buildEvaluationReferenceSectionSafe("java-backend"))
            .thenReturn("### JVM\n垃圾回收与内存模型");

        AgentContextService service = new AgentContextService(history, skills, rag, sanitizer, objectMapper);
        AgentContextService.AgentContext context = service.build(
            "session-1", new AgentMessageRequest("review", "我应该先补什么？", List.of(), null)
        );

        assertThat(context.skillReference()).contains("JVM");
        assertThat(context.ragContext()).contains("暂无知识库检索上下文");
        assertThat(context.sources()).singleElement().satisfies(source -> {
            assertThat(source.kind()).isEqualTo("skill");
            assertThat(source.reference()).isEqualTo("skill-reference");
        });
    }

    @Test
    void shouldLoadKnowledgeSourcesForKnowledgeBaseInterview() throws Exception {
        InterviewHistoryService history = mock(InterviewHistoryService.class);
        InterviewSkillService skills = mock(InterviewSkillService.class);
        KnowledgeBaseQueryService rag = mock(KnowledgeBaseQueryService.class);
        PromptSanitizer sanitizer = mock(PromptSanitizer.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        InterviewDetailDTO detail = detail(null, 42L);

        when(history.getInterviewDetail("session-2")).thenReturn(detail);
        when(objectMapper.writeValueAsString(detail)).thenReturn("{report}");
        when(sanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rag.retrieveOnly(any(), anyString(), any())).thenReturn(new RagQueryExecution(
            "原问题", "改写问题", List.of("改写问题"), 8, 0.28,
            List.of(new RagQueryExecution.RetrievedDoc(
                1, "Redis 使用跳表支持有序集合", 0.88, Map.of("source_name", "Redis 参考资料")
            )), 1, 2, 0, null, "RETRIEVED"
        ));

        AgentContextService service = new AgentContextService(history, skills, rag, sanitizer, objectMapper);
        AgentContextService.AgentContext context = service.build(
            "session-2", new AgentMessageRequest("practice", "帮我练习缓存", List.of(), null)
        );

        assertThat(context.ragContext()).contains("Redis 使用跳表");
        assertThat(context.sources()).singleElement().satisfies(source -> {
            assertThat(source.kind()).isEqualTo("rag");
            assertThat(source.reference()).isEqualTo("R1");
            assertThat(source.score()).isEqualTo(0.88);
        });
    }

    private InterviewDetailDTO detail(String skillId, Long knowledgeBaseId) {
        return new InterviewDetailDTO(
            1L, "session", 1, "COMPLETED", "COMPLETED", null, 60,
            knowledgeBaseId == null ? "NORMAL" : "KNOWLEDGE_BASE", knowledgeBaseId, skillId,
            "需要继续练习", null, null, List.of(), List.of("补充缓存知识"),
            List.of(), List.of(), List.of()
        );
    }
}
