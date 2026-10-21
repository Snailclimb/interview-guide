package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationServiceTest {

    @Mock
    private AgentChatSessionRepository sessionRepository;

    @Mock
    private AgentChatMessageRepository messageRepository;

    @InjectMocks
    private AgentConversationService service;

    @Test
    @DisplayName("准备 Agent 消息时创建独立会话、用户消息和助手占位消息")
    void prepareMessageCreatesSessionAndMessagePair() {
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
            .thenReturn(Optional.empty());
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.empty());
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> {
                AgentChatSessionEntity session = invocation.getArgument(0);
                session.setId(11L);
                return session;
            });
        when(messageRepository.findTopBySessionIdOrderBySequenceNumDesc(11L))
            .thenReturn(Optional.empty());
        when(messageRepository.save(any(AgentChatMessageEntity.class)))
            .thenAnswer(invocation -> {
                AgentChatMessageEntity message = invocation.getArgument(0);
                if (message.getRole() == AgentChatMessageEntity.MessageRole.ASSISTANT) {
                    message.setId(22L);
                }
                return message;
            });

        AgentConversationService.PreparedMessage prepared = service.prepareMessage(
            "interview-1", "review", null, "请复盘一下"
        );

        assertThat(prepared.assistantMessageId()).isEqualTo(22L);
        ArgumentCaptor<AgentChatMessageEntity> captor =
            ArgumentCaptor.forClass(AgentChatMessageEntity.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(AgentChatMessageEntity::getRole)
            .containsExactly(
                AgentChatMessageEntity.MessageRole.USER,
                AgentChatMessageEntity.MessageRole.ASSISTANT
            );
        assertThat(captor.getAllValues().get(0).getContent()).isEqualTo("请复盘一下");
        assertThat(captor.getAllValues().get(1).getCompleted()).isFalse();
    }

    @Test
    @DisplayName("恢复会话时只返回已完成且有内容的消息")
    void getConversationFiltersIncompleteMessages() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .goal("review")
            .providerId("pixel")
            .build();
        AgentChatMessageEntity user = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content("请复盘")
            .sequenceNum(0)
            .completed(true)
            .build();
        AgentChatMessageEntity assistant = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("复盘结果")
            .sequenceNum(1)
            .completed(true)
            .build();
        AgentChatMessageEntity incomplete = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("半截回答")
            .sequenceNum(2)
            .completed(false)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(11L), any(Pageable.class)
        ))
            .thenReturn(List.of(assistant, user));

        AgentConversationResponse response = service.getConversation("interview-1");

        assertThat(response.goal()).isEqualTo("review");
        assertThat(response.providerId()).isEqualTo("pixel");
        assertThat(response.messages()).extracting("role")
            .containsExactly("user", "assistant");
        assertThat(response.messages()).extracting("content")
            .containsExactly("请复盘", "复盘结果");
    }
}
