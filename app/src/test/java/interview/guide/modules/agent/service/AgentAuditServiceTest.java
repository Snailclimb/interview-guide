package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void defersAuditUntilBusinessTransactionCommits() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.recordQuietly(
                "user-1", "interview-1", "agent-1", "run-1", "RUN_COMPLETED",
                "agent-run", "run-1", "success"
            );
            verify(repository, never()).save(any(AgentAuditEventEntity.class));

            TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
            synchronization.afterCommit();
            verify(repository).save(any(AgentAuditEventEntity.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void listsOnlyCurrentOwnerEventsAndHonorsSmallLimit() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1"))
            .thenReturn(List.of(
                AgentAuditEventEntity.builder()
                    .interviewSessionId("interview-1")
                    .agentSessionId("agent-1")
                    .action("RUN_COMPLETED")
                    .resourceType("agent-run")
                    .resourceId("run-1")
                    .result("COMPLETED")
                    .createdAt(LocalDateTime.now())
                    .build(),
                AgentAuditEventEntity.builder()
                    .action("MEMORY_UPDATED")
                    .resourceType("memory-item")
                    .resourceId("item-1")
                    .createdAt(LocalDateTime.now().minusMinutes(1))
                    .build()
            ));

        var events = new AgentAuditService(repository).listForOwner(" user-1 ", 1);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().action()).isEqualTo("RUN_COMPLETED");
        verify(repository).findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1");
    }
}
