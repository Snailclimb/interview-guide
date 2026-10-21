package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Agent 对话存储服务。
 *
 * <p>流式输出不会逐 token 写数据库，只在开始、完成、失败或取消时更新，避免
 * 长回答产生大量数据库写入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    private static final int MAX_PERSISTED_HISTORY_MESSAGES = 64;

    private final AgentChatSessionRepository sessionRepository;
    private final AgentChatMessageRepository messageRepository;

    @Transactional
    public PreparedMessage prepareMessage(
        String interviewSessionId,
        String goal,
        String providerId,
        String userContent
    ) {
        AgentChatSessionEntity session = sessionRepository.findByInterviewSessionIdForUpdate(interviewSessionId)
            .orElseGet(() -> sessionRepository.findByInterviewSessionId(interviewSessionId)
                .orElseGet(() -> AgentChatSessionEntity.builder()
                    .interviewSessionId(interviewSessionId)
                    .build()));
        session.setGoal(goal);
        if (providerId != null && !providerId.isBlank()) {
            session.setProviderId(providerId);
        }
        session = sessionRepository.save(session);

        int nextSequence = messageRepository.findTopBySessionIdOrderBySequenceNumDesc(session.getId())
            .map(message -> message.getSequenceNum() + 1)
            .orElse(0);
        AgentChatMessageEntity userMessage = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content(userContent)
            .sequenceNum(nextSequence)
            .completed(true)
            .build();
        messageRepository.save(userMessage);

        AgentChatMessageEntity assistantMessage = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("")
            .sequenceNum(nextSequence + 1)
            .completed(false)
            .build();
        assistantMessage = messageRepository.save(assistantMessage);
        return new PreparedMessage(assistantMessage.getId(), session.getId());
    }

    @Transactional
    public void completeMessage(Long messageId, String content) {
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setContent(content == null ? "" : content);
            message.setCompleted(true);
            message.setErrorMessage(null);
            messageRepository.save(message);
        });
    }

    @Transactional
    public void failMessage(Long messageId, String partialContent, String errorMessage) {
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setContent(partialContent == null ? "" : partialContent);
            message.setCompleted(false);
            message.setErrorMessage(truncate(errorMessage, 500));
            messageRepository.save(message);
        });
    }

    @Transactional
    public void cancelMessage(Long messageId, String partialContent) {
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setContent(partialContent == null ? "" : partialContent);
            message.setCompleted(false);
            message.setErrorMessage("客户端中断了本次回答");
            messageRepository.save(message);
        });
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId) {
        AgentChatSessionEntity session = sessionRepository.findByInterviewSessionId(interviewSessionId)
            .orElse(null);
        if (session == null) {
            return new AgentConversationResponse(interviewSessionId, null, null, List.of());
        }

        List<AgentChatMessageEntity> recentMessages = messageRepository
            .findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
                session.getId(), PageRequest.of(0, MAX_PERSISTED_HISTORY_MESSAGES)
            );
        List<AgentHistoryMessage> messages = new ArrayList<>();
        for (AgentChatMessageEntity message : recentMessages.reversed()) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            messages.add(new AgentHistoryMessage(message.getRole().name().toLowerCase(), message.getContent()));
        }
        List<AgentHistoryMessage> normalized = AgentHistoryPolicy.normalize(messages);
        return new AgentConversationResponse(
            interviewSessionId,
            session.getGoal(),
            session.getProviderId(),
            normalized
        );
    }

    public List<AgentHistoryMessage> loadHistory(String interviewSessionId) {
        return getConversation(interviewSessionId).messages();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record PreparedMessage(Long assistantMessageId, Long sessionId) {
    }
}
