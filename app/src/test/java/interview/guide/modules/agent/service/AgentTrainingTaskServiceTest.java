package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentTrainingTaskEntity;
import interview.guide.modules.agent.model.AgentTrainingTaskStatus;
import interview.guide.modules.agent.repository.AgentTrainingTaskRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTrainingTaskServiceTest {

    @Mock
    private AgentTrainingTaskRepository repository;

    @Mock
    private AgentAuditService auditService;

    @Test
    void createsTaskOnceForConfirmedAction() {
        AgentTrainingTaskService service = new AgentTrainingTaskService(repository, auditService);
        AgentActionRequestEntity action = action("action-1", "owner-1");
        when(repository.findBySourceActionId("action-1")).thenReturn(Optional.empty());
        when(repository.save(any(AgentTrainingTaskEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createFromAction(action);

        assertThat(created.status()).isEqualTo(AgentTrainingTaskStatus.TODO);
        assertThat(created.title()).isEqualTo("先补 Redis 缓存击穿");
        assertThat(created.description()).contains("消息幂等");
        verify(repository).save(any(AgentTrainingTaskEntity.class));

        AgentTrainingTaskEntity existing = AgentTrainingTaskEntity.builder()
            .taskId(created.taskId())
            .ownerKey("owner-1")
            .sourceActionId("action-1")
            .title(created.title())
            .description(created.description())
            .status(AgentTrainingTaskStatus.TODO)
            .build();
        when(repository.findBySourceActionId("action-1")).thenReturn(Optional.of(existing));
        assertThat(service.createFromAction(action).taskId()).isEqualTo(created.taskId());
        verify(repository, org.mockito.Mockito.times(1)).save(any(AgentTrainingTaskEntity.class));
    }

    @Test
    void onlyOwnerCanAdvanceAndCompletedTaskCannotRollback() {
        AgentTrainingTaskService service = new AgentTrainingTaskService(repository, auditService);
        AgentTrainingTaskEntity task = AgentTrainingTaskEntity.builder()
            .taskId("task-1")
            .ownerKey("owner-1")
            .status(AgentTrainingTaskStatus.TODO)
            .build();
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(task));
        when(repository.save(any(AgentTrainingTaskEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.updateStatus("task-1", "owner-2", AgentTrainingTaskStatus.IN_PROGRESS))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");

        assertThat(service.updateStatus("task-1", "owner-1", AgentTrainingTaskStatus.IN_PROGRESS).status())
            .isEqualTo(AgentTrainingTaskStatus.IN_PROGRESS);
        assertThat(service.updateStatus("task-1", "owner-1", AgentTrainingTaskStatus.COMPLETED).status())
            .isEqualTo(AgentTrainingTaskStatus.COMPLETED);
        assertThatThrownBy(() -> service.updateStatus("task-1", "owner-1", AgentTrainingTaskStatus.TODO))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已结束");
    }

    @Test
    void rejectsEmptyTaskContent() {
        AgentTrainingTaskService service = new AgentTrainingTaskService(repository, auditService);
        AgentActionRequestEntity action = action("action-1", "owner-1");
        action.setDraftContent(" ");

        assertThatThrownBy(() -> service.createFromAction(action))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内容不能为空");
        verify(repository, never()).save(any(AgentTrainingTaskEntity.class));
    }

    private AgentActionRequestEntity action(String actionId, String owner) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .draftContent("## 先补 Redis 缓存击穿\n\n- 每天练消息幂等")
            .build();
    }
}
