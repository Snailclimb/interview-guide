package interview.guide.modules.agent;

import interview.guide.common.result.Result;
import interview.guide.modules.agent.model.AgentMemoryResponse;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.service.AgentConversationService;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.InterviewAgentService;
import interview.guide.modules.auth.AuthIdentityService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController 长期记忆接口")
class AgentControllerTest {

    @Mock private InterviewAgentService agentService;
    @Mock private AgentConversationService conversationService;
    @Mock private AgentMemoryService memoryService;
    @Mock private AuthIdentityService authIdentityService;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private AgentController controller;

    @Test
    @DisplayName("查询记忆只返回结构化摘要，不返回内部 memoryKey")
    void returnsMemorySummary() {
        when(authIdentityService.requireIdentity("user-1")).thenReturn("user-1");
        when(memoryService.load("user-1")).thenReturn(new AgentMemorySnapshot(
            "internal-key", "java-backend", List.of("Redis"), List.of("practice"),
            LocalDateTime.of(2026, 10, 25, 12, 0)
        ));

        Result<AgentMemoryResponse> result = controller.getMemory("user-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().lastSkillId()).isEqualTo("java-backend");
        assertThat(result.getData().weaknesses()).containsExactly("Redis");
        assertThat(result.getData().toString()).doesNotContain("internal-key");
    }
}
