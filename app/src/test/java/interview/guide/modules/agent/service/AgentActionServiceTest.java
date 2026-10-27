package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentActionServiceTest {

    @Mock
    private AgentActionRequestRepository repository;

    @Mock
    private AgentAuditService auditService;

    @Test
    @DisplayName("生成训练计划草稿时默认只提出动作，不改原始资料")
    void proposesDraftWithStableContract() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "request-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.proposeTrainingPlanDraft(
            "interview-1", "agent-1", "user-1", "  ## 训练计划\n\n先补 Redis  ", "request-1"
        );

        assertThat(result.status()).isEqualTo(AgentActionStatus.PROPOSED);
        assertThat(result.toolId()).isEqualTo("interview.plan.draft");
        assertThat(result.riskLevel()).isEqualTo("DRAFT_WRITE");
        assertThat(result.draftContent()).isEqualTo("## 训练计划\n\n先补 Redis");
        assertThat(result.previewText()).contains("不修改原始面试报告");
        ArgumentCaptor<AgentActionRequestEntity> captor =
            ArgumentCaptor.forClass(AgentActionRequestEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AgentActionStatus.PROPOSED);
        verify(auditService).recordQuietly(
            "user-1", "interview-1", "agent-1", null,
            "ACTION_PROPOSED", "agent-action", result.actionId(), "PROPOSED"
        );
    }

    @Test
    @DisplayName("重复提交相同幂等键时返回原动作，不创建第二份草稿")
    void returnsExistingActionForIdempotencyKey() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity existing = AgentActionRequestEntity.builder()
            .actionId("action-1")
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("interview.plan.draft")
            .riskLevel("DRAFT_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("训练计划草稿")
            .previewText("预览")
            .draftContent("草稿")
            .idempotencyKey("request-1")
            .build();
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "request-1"))
            .thenReturn(Optional.of(existing));

        var result = service.proposeTrainingPlanDraft(
            "interview-1", "agent-1", "user-1", "另一份内容", "request-1"
        );

        assertThat(result.actionId()).isEqualTo("action-1");
        assertThat(result.draftContent()).isEqualTo("草稿");
        verify(repository, never()).save(any());
        verify(auditService, never()).recordQuietly(
            any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("确认动作按状态机完成，并且重复确认是幂等的")
    void confirmsActionIdempotently() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("action-1", "user-1");
        when(repository.findByActionIdForUpdate("action-1")).thenReturn(Optional.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm("action-1", "user-1");
        var repeated = service.confirm("action-1", "user-1");

        assertThat(result.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(result.resultSummary()).contains("原始面试资料未被修改");
        assertThat(repeated.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(repository).save(action);
    }

    @Test
    @DisplayName("其他用户不能确认动作")
    void rejectsCrossOwnerConfirmation() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findByActionIdForUpdate("action-1"))
            .thenReturn(Optional.of(draft("action-1", "owner-a")));

        assertThatThrownBy(() -> service.confirm("action-1", "owner-b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
    }

    private AgentActionRequestEntity draft(String actionId, String owner) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("interview.plan.draft")
            .riskLevel("DRAFT_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("训练计划草稿")
            .previewText("预览")
            .draftContent("草稿")
            .idempotencyKey("request-1")
            .build();
    }
}
