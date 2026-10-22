package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
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
    private static final int STALE_RUN_MINUTES = 15;

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
        recoverStaleRun(session);
        if (session.getRunStatus() == AgentRunStatus.RUNNING) {
            throw new AgentTaskBusyException();
        }

        String runId = UUID.randomUUID().toString();
        session.setGoal(goal);
        if (providerId != null && !providerId.isBlank()) {
            session.setProviderId(providerId);
        }
        session.setRunId(runId);
        session.setRunStatus(AgentRunStatus.RUNNING);
        session.setRunStartedAt(LocalDateTime.now());
        session.setRunFinishedAt(null);
        session.setRunError(null);
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
        session.setActiveMessageId(assistantMessage.getId());
        sessionRepository.save(session);
        return new PreparedMessage(assistantMessage.getId(), session.getId(), runId);
    }

    @Transactional
    public void completeMessage(Long messageId, String content) {
        messageRepository.findById(messageId).ifPresent(message -> {
            if (!isActiveMessage(message)) {
                log.info("忽略已失效 Agent 完成事件: messageId={}", messageId);
                return;
            }
            message.setContent(content == null ? "" : content);
            message.setCompleted(true);
            message.setErrorMessage(null);
            messageRepository.save(message);
            markRunFinished(message, AgentRunStatus.COMPLETED, null);
        });
    }

    @Transactional
    public void failMessage(Long messageId, String partialContent, String errorMessage) {
        messageRepository.findById(messageId).ifPresent(message -> {
            if (!isActiveMessage(message)) {
                log.info("忽略已失效 Agent 失败事件: messageId={}", messageId);
                return;
            }
            message.setContent(partialContent == null ? "" : partialContent);
            message.setCompleted(false);
            message.setErrorMessage(truncate(errorMessage, 500));
            messageRepository.save(message);
            markRunFinished(message, AgentRunStatus.FAILED, errorMessage);
        });
    }

    @Transactional
    public void cancelMessage(Long messageId, String partialContent) {
        messageRepository.findById(messageId).ifPresent(message -> {
            if (!isActiveMessage(message)) {
                log.info("忽略已失效 Agent 取消事件: messageId={}", messageId);
                return;
            }
            message.setContent(partialContent == null ? "" : partialContent);
            message.setCompleted(false);
            message.setErrorMessage("客户端中断了本次回答");
            messageRepository.save(message);
            markRunFinished(message, AgentRunStatus.CANCELLED, "客户端中断了本次回答");
        });
    }

    @Transactional
    public boolean cancelRun(String interviewSessionId) {
        AgentChatSessionEntity session = sessionRepository
            .findByInterviewSessionIdForUpdate(interviewSessionId)
            .orElse(null);
        if (session == null || session.getRunStatus() != AgentRunStatus.RUNNING) {
            return false;
        }
        if (session.getActiveMessageId() != null) {
            cancelMessage(session.getActiveMessageId(), "用户请求取消本次回答");
        } else {
            markRunFinished(session, AgentRunStatus.CANCELLED, "用户请求取消本次回答");
        }
        return true;
    }

    /**
     * 判断当前后台任务是否仍是该面试会话的有效运行任务。
     * 用于多实例部署时让执行任务感知另一实例发起的取消或超时回收。
     */
    @Transactional
    public boolean isRunActive(String interviewSessionId, String runId) {
        return sessionRepository.findByInterviewSessionId(interviewSessionId)
            .map(session -> session.getRunStatus() == AgentRunStatus.RUNNING
                && runId != null
                && runId.equals(session.getRunId()))
            .orElse(false);
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId) {
        AgentChatSessionEntity session = sessionRepository.findByInterviewSessionId(interviewSessionId)
            .orElse(null);
        if (session == null) {
            return new AgentConversationResponse(
                interviewSessionId, null, null, null, AgentRunStatus.IDLE,
                null, null, null, List.of()
            );
        }
        if (isStaleRun(session)) {
            session = sessionRepository.findByInterviewSessionIdForUpdate(interviewSessionId)
                .orElse(session);
            recoverStaleRun(session);
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
            session.getRunId(),
            session.getRunStatus(),
            session.getRunStartedAt(),
            session.getRunFinishedAt(),
            session.getRunError(),
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

    private void markRunFinished(AgentChatMessageEntity message, AgentRunStatus status, String error) {
        AgentChatSessionEntity session = message.getSession();
        if (session == null) {
            return;
        }
        markRunFinished(session, status, error);
    }

    private void markRunFinished(AgentChatSessionEntity session, AgentRunStatus status, String error) {
        session.setRunStatus(status);
        session.setRunFinishedAt(LocalDateTime.now());
        session.setActiveMessageId(null);
        session.setRunError(truncate(error, 500));
        sessionRepository.save(session);
    }

    private boolean isActiveMessage(AgentChatMessageEntity message) {
        AgentChatSessionEntity session = message.getSession();
        return session != null
            && message.getId() != null
            && message.getId().equals(session.getActiveMessageId())
            && session.getRunStatus() == AgentRunStatus.RUNNING;
    }

    private void recoverStaleRun(AgentChatSessionEntity session) {
        if (!isStaleRun(session)) {
            return;
        }
        if (session.getActiveMessageId() != null) {
            messageRepository.findById(session.getActiveMessageId()).ifPresent(message -> {
                message.setCompleted(false);
                message.setErrorMessage("Agent 任务超过最大执行时间，已自动回收");
                messageRepository.save(message);
            });
        }
        markRunFinished(session, AgentRunStatus.FAILED, "Agent 任务超过最大执行时间，已自动回收");
    }

    private boolean isStaleRun(AgentChatSessionEntity session) {
        return session != null
            && session.getRunStatus() == AgentRunStatus.RUNNING
            && session.getRunStartedAt() != null
            && !session.getRunStartedAt().isAfter(LocalDateTime.now().minusMinutes(STALE_RUN_MINUTES));
    }

    public record PreparedMessage(Long assistantMessageId, Long sessionId, String runId) {
    }
}
