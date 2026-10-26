package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAuditServiceTest {

    @Test
    void recordsOnlyBoundedLowSensitivityFields() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);

        service.recordQuietly(
            " user-1 ", "interview-1", "agent-1", "run-1", "MEMORY_UPDATED",
            "memory-item", "item-1", "success"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        AgentAuditEventEntity event = captor.getValue();
        assertThat(event.getOwnerKey()).isEqualTo("user-1");
        assertThat(event.getAction()).isEqualTo("MEMORY_UPDATED");
        assertThat(event.getResourceType()).isEqualTo("memory-item");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void auditFailureDoesNotEscapeToAgentFlow() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenThrow(new IllegalStateException("audit unavailable"));

        new AgentAuditService(repository).recordQuietly(
            "user-1", null, null, null, "RUN_STARTED", "agent-run", "run-1", "success"
        );
    }
}
