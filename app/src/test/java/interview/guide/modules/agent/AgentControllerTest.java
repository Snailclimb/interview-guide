package interview.guide.modules.agent;

import interview.guide.common.result.Result;
import interview.guide.modules.agent.model.AgentMemoryResponse;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.model.CreateAgentDraftActionRequest;
import interview.guide.modules.agent.model.CreateAgentMemoryDraftRequest;
import interview.guide.modules.agent.model.CreateAgentTitleDraftRequest;
import interview.guide.modules.agent.model.UpdateAgentMemoryItemRequest;
import interview.guide.modules.agent.service.AgentActionService;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController 长期记忆接口")
class AgentControllerTest {

    @Mock private InterviewAgentService agentService;
    @Mock private AgentConversationService conversationService;
    @Mock private AgentMemoryService memoryService;
    @Mock private AgentActionService actionService;
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

    @Test
    @DisplayName("编辑和删除记忆项使用当前身份并返回最新摘要")
    void managesMemoryItem() {
        when(authIdentityService.requireIdentity("user-1")).thenReturn("user-1");
        AgentMemorySnapshot snapshot = new AgentMemorySnapshot(
            "internal-key", "java-backend", List.of("Redis"), List.of(),
            LocalDateTime.of(2026, 10, 25, 12, 0)
        );
        when(memoryService.updateItem("user-1", "w-1", "Redis 穿透", "java-backend"))
            .thenReturn(snapshot);
        when(memoryService.deleteItem("user-1", "w-1")).thenReturn(snapshot);

        Result<AgentMemoryResponse> updated = controller.updateMemoryItem(
            "w-1", new UpdateAgentMemoryItemRequest("Redis 穿透", "java-backend"), "user-1"
        );
        Result<AgentMemoryResponse> deleted = controller.deleteMemoryItem("w-1", "user-1");

        assertThat(updated.isSuccess()).isTrue();
        assertThat(deleted.isSuccess()).isTrue();
        org.mockito.Mockito.verify(memoryService)
            .updateItem("user-1", "w-1", "Redis 穿透", "java-backend");
        org.mockito.Mockito.verify(memoryService).deleteItem("user-1", "w-1");
    }

    @Test
    @DisplayName("动作接口先校验当前 Agent 会话，再创建记忆和改名草稿")
    void proposesNewSafeActionsAfterAccessCheck() {
        when(authIdentityService.requireIdentity("user-1")).thenReturn("user-1");
        AgentActionResponse action = new AgentActionResponse(
            "action-1", "interview-1", "agent-1", "agent.memory.save", "MEMORY_WRITE",
            AgentActionStatus.PROPOSED, "长期记忆草稿", "预览", "Redis 薄弱点", null,
            null, null, null, null
        );
        when(actionService.proposeMemoryDraft(
            "interview-1", "agent-1", "user-1", "Redis 薄弱点", "weakness", "java-backend", "memory-1"
        )).thenReturn(action);

        Result<AgentActionResponse> memoryResult = controller.proposeMemoryDraft(
            "interview-1", "agent-1",
            new CreateAgentMemoryDraftRequest("Redis 薄弱点", "weakness", "java-backend", "memory-1"),
            "user-1"
        );
        Result<AgentActionResponse> titleResult = controller.proposeInterviewTitleDraft(
            "interview-1", "agent-1",
            new CreateAgentTitleDraftRequest("Java 后端专项复盘", "title-1"),
            "user-1"
        );

        assertThat(memoryResult.isSuccess()).isTrue();
        assertThat(memoryResult.getData().toolId()).isEqualTo("agent.memory.save");
        assertThat(titleResult).isNotNull();
        verify(conversationService, org.mockito.Mockito.times(2))
            .assertAccess("interview-1", "agent-1", "user-1");
        verify(actionService).proposeInterviewTitleDraft(
            "interview-1", "agent-1", "user-1", "Java 后端专项复盘", "title-1"
        );
    }
}
