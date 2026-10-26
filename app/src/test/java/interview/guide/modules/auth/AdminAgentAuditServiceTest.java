package interview.guide.modules.auth;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAgentAuditServiceTest {
    @Mock
    private AgentAuditEventRepository repository;

    @Test
    void limitsAndMapsLowSensitivityAgentEvents() {
        AgentAuditEventEntity event = AgentAuditEventEntity.builder()
            .id(7L)
            .ownerKey("user-1")
            .agentSessionId("agent-1")
            .action("TOOL_CALLED")
            .resourceType("agent-tool")
            .resourceId("interview_skills")
            .result("success")
            .createdAt(LocalDateTime.now())
            .build();
        when(repository.findTop200ByOrderByCreatedAtDesc()).thenReturn(List.of(event));

        var result = new AdminAgentAuditService(repository).list(999);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ownerKey()).isEqualTo("user-1");
        assertThat(result.getFirst().action()).isEqualTo("TOOL_CALLED");
    }
}
