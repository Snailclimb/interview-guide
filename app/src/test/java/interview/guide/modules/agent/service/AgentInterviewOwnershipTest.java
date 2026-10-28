package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentInterviewOwnershipTest {
    private final AgentChatSessionRepository sessions = mock(AgentChatSessionRepository.class);
    private final AgentChatMessageRepository messages = mock(AgentChatMessageRepository.class);
    private final InterviewPersistenceService interviews = mock(InterviewPersistenceService.class);
    private final AgentConversationService service = new AgentConversationService(
        sessions, messages, mock(ObjectMapper.class), interviews
    );

    @AfterEach
    void clearUserContext() {
        AuthUserContext.clear();
    }

    @Test
    void formalUserCannotUseAnotherUsersInterviewAsAgentContext() {
        AuthUserContext.set("user-b");
        when(interviews.findBySessionIdForOwnerOrClaimLegacy("interview-a", "user-b"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertAccess("interview-a", null, "user-b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
    }
}
