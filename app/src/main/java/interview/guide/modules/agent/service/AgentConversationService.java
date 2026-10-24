package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentConversationSummary;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
    private static final int MAX_RUN_SOURCES_JSON_CHARS = 12_000;

    private final AgentChatSessionRepository sessionRepository;
    private final AgentChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public PreparedMessage prepareMessage(
        String interviewSessionId,
        String goal,
        String providerId,
        String userContent
    ) {
        return prepareMessage(interviewSessionId, null, goal, providerId, userContent, null);
    }

    @Transactional
    public PreparedMessage prepareMessage(
        String interviewSessionId,
        String goal,
        String providerId,
        String userContent,
        String memoryKey
    ) {
        return prepareMessage(interviewSessionId, null, goal, providerId, userContent, memoryKey);
    }

    @Transactional
    public PreparedMessage prepareMessage(
        String interviewSessionId,
        String agentSessionId,
        String goal,
        String providerId,
        String userContent,
        String memoryKey
    ) {
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .orElseGet(() -> AgentChatSessionEntity.builder()
                .interviewSessionId(interviewSessionId)
                .agentSessionId(UUID.randomUUID().toString())
                .title("新 Agent 对话")
                .build());
        ensureSessionIdentity(session, interviewSessionId);
        recoverStaleRun(session);
        if (session.getRunStatus() == AgentRunStatus.RUNNING) {
            throw new AgentTaskBusyException();
        }

        String runId = UUID.randomUUID().toString();
        session.setGoal(goal);
        if (memoryKey != null && !memoryKey.isBlank()) {
            session.setMemoryKey(memoryKey.trim());
        }
        if (providerId != null && !providerId.isBlank()) {
            session.setProviderId(providerId);
        }
        if (session.getTitle() == null || session.getTitle().isBlank()
            || "新 Agent 对话".equals(session.getTitle())) {
            session.setTitle(truncate(userContent, 40));
        }
        session.setRunId(runId);
        session.setRunStatus(AgentRunStatus.RUNNING);
        session.setRunStartedAt(LocalDateTime.now());
        session.setRunFinishedAt(null);
        session.setRunError(null);
        session.setRunStep(null);
        session.setRunStepStatus(null);
        session.setRunNotice(null);
        session.setRunSourcesJson(null);
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
        return new PreparedMessage(assistantMessage.getId(), session.getId(), runId, session.getAgentSessionId());
    }

    /** 返回指定面试报告下的稳定 Agent 对话标识；旧调用方不传 ID 时复用默认线程。 */
    @Transactional
    public String resolveAgentSessionId(String interviewSessionId, String requestedAgentSessionId) {
        return findSession(interviewSessionId, requestedAgentSessionId)
            .map(session -> {
                ensureSessionIdentity(session, interviewSessionId);
                return session.getAgentSessionId();
            })
            .orElseGet(() -> {
                AgentChatSessionEntity session = AgentChatSessionEntity.builder()
                    .interviewSessionId(interviewSessionId)
                    .agentSessionId(UUID.randomUUID().toString())
                    .title("新 Agent 对话")
                    .build();
                return sessionRepository.save(session).getAgentSessionId();
            });
    }

    @Transactional
    public AgentConversationSummary createConversation(String interviewSessionId, String title) {
        String normalizedTitle = title == null || title.isBlank() ? "新 Agent 对话" : truncate(title.trim(), 120);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId(interviewSessionId)
            .agentSessionId(UUID.randomUUID().toString())
            .title(normalizedTitle)
            .build();
        return toSummary(sessionRepository.save(session));
    }

    @Transactional
    public List<AgentConversationSummary> listConversations(String interviewSessionId) {
        return sessionRepository.findByInterviewSessionIdOrderByUpdatedAtDesc(interviewSessionId).stream()
            .map(this::toSummary)
            .toList();
    }

    @Transactional
    public void completeMessage(Long messageId, String content) {
        AgentChatMessageEntity message = lockActiveMessage(messageId);
        if (message == null) {
            log.info("忽略已失效 Agent 完成事件: messageId={}", messageId);
            return;
        }
        updateTerminalMessage(message, content, true, null);
        markRunFinished(message.getSession(), AgentRunStatus.COMPLETED, null);
    }

    @Transactional
    public void failMessage(Long messageId, String partialContent, String errorMessage) {
        String safeError = truncate(errorMessage, 500);
        AgentChatMessageEntity message = lockActiveMessage(messageId);
        if (message == null) {
            log.info("忽略已失效 Agent 失败事件: messageId={}", messageId);
            return;
        }
        updateTerminalMessage(message, partialContent, false, safeError);
        markRunFinished(message.getSession(), AgentRunStatus.FAILED, safeError);
    }

    @Transactional
    public void cancelMessage(Long messageId, String partialContent) {
        String error = "客户端中断了本次回答";
        AgentChatMessageEntity message = lockActiveMessage(messageId);
        if (message == null) {
            log.info("忽略已失效 Agent 取消事件: messageId={}", messageId);
            return;
        }
        updateTerminalMessage(message, partialContent, false, error);
        markRunFinished(message.getSession(), AgentRunStatus.CANCELLED, error);
    }

    @Transactional
    public boolean cancelRun(String interviewSessionId) {
        return cancelRun(interviewSessionId, null);
    }

    @Transactional
    public boolean cancelRun(String interviewSessionId, String agentSessionId) {
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
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

    /** 持久化当前 Agent 编排阶段，供断线和页面刷新后恢复展示。 */
    @Transactional
    public void updateRunProgress(Long messageId, String step, String status, String notice) {
        AgentChatSessionEntity session = lockActiveSession(messageId);
        if (session == null) {
            log.info("忽略已失效 Agent 进度事件: messageId={}", messageId);
            return;
        }
        session.setRunStep(truncate(step, 32));
        session.setRunStepStatus(truncate(status, 16));
        session.setRunNotice(truncate(notice, 500));
        sessionRepository.save(session);
    }

    /** 持久化本次 Agent 实际使用的来源摘要，不保存原始 RAG 文本。 */
    @Transactional
    public void updateRunSources(Long messageId, List<AgentSource> sources) {
        String serialized = serializeSources(sources);
        AgentChatSessionEntity session = lockActiveSession(messageId);
        if (session == null) {
            log.info("忽略已失效 Agent 来源事件: messageId={}", messageId);
            return;
        }
        session.setRunSourcesJson(serialized);
        sessionRepository.save(session);
    }

    /**
     * 判断当前后台任务是否仍是该面试会话的有效运行任务。
     * 用于多实例部署时让执行任务感知另一实例发起的取消或超时回收。
     */
    @Transactional
    public boolean isRunActive(String interviewSessionId, String runId) {
        return isRunActive(interviewSessionId, null, runId);
    }

    @Transactional
    public boolean isRunActive(String interviewSessionId, String agentSessionId, String runId) {
        return findSession(interviewSessionId, agentSessionId)
            .map(session -> session.getRunStatus() == AgentRunStatus.RUNNING
                && runId != null
                && runId.equals(session.getRunId()))
            .orElse(false);
    }

    /**
     * 启动恢复的数据库栅栏：锁住一条过期 RUNNING 会话，并立即换发新的 runId。
     * 这样多个实例同时扫描时只有一个实例能接管任务。
     */
    @Transactional
    public RecoveredRun claimRecoverableRun(String interviewSessionId, LocalDateTime staleBefore) {
        return claimRecoverableRun(null, interviewSessionId, staleBefore);
    }

    @Transactional
    public RecoveredRun claimRecoverableRun(
        String agentSessionId, String interviewSessionId, LocalDateTime staleBefore
    ) {
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .orElse(null);
        if (session == null
            || session.getRunStatus() != AgentRunStatus.RUNNING
            || session.getRunStartedAt() == null
            || session.getRunStartedAt().isAfter(staleBefore)
            || session.getActiveMessageId() == null) {
            return null;
        }

        AgentChatMessageEntity assistant = messageRepository.findById(session.getActiveMessageId()).orElse(null);
        if (assistant == null || assistant.getCompleted()) {
            return null;
        }
        AgentChatMessageEntity user = messageRepository
            .findBySessionIdAndCompletedTrueOrderBySequenceNumAsc(session.getId())
            .stream()
            .filter(item -> item.getRole() == AgentChatMessageEntity.MessageRole.USER)
            .filter(item -> item.getSequenceNum() < assistant.getSequenceNum())
            .reduce((first, second) -> second)
            .orElse(null);
        if (user == null || user.getContent() == null || user.getContent().isBlank()) {
            return null;
        }

        String runId = UUID.randomUUID().toString();
        session.setRunId(runId);
        session.setRunStartedAt(LocalDateTime.now());
        session.setRunFinishedAt(null);
        session.setRunError(null);
        session.setRunStep("context");
        session.setRunStepStatus("running");
        session.setRunNotice("应用重启后自动恢复中");
        session.setRunSourcesJson(null);
        assistant.setContent("");
        assistant.setCompleted(false);
        assistant.setErrorMessage(null);
        messageRepository.save(assistant);
        sessionRepository.save(session);
        return new RecoveredRun(
            session.getInterviewSessionId(),
            session.getAgentSessionId(),
            session.getGoal(),
            session.getProviderId(),
            session.getMemoryKey(),
            user.getContent(),
            assistant.getId(),
            session.getRunId()
        );
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId) {
        return getConversation(interviewSessionId, null);
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId, String agentSessionId) {
        AgentChatSessionEntity session = findSession(interviewSessionId, agentSessionId)
            .orElse(null);
        if (session == null) {
            return new AgentConversationResponse(
                interviewSessionId, null, null, null, null, null, AgentRunStatus.IDLE,
                null, null, null, null, null, null, null, List.of(), List.of()
            );
        }
        if (isStaleRun(session)) {
            session = findSessionForUpdate(interviewSessionId, agentSessionId)
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
        String partialAnswer = shouldExposePartialAnswer(session.getRunStatus())
            ? messageRepository
                .findTopBySessionIdAndCompletedFalseOrderBySequenceNumDesc(session.getId())
                .map(AgentChatMessageEntity::getContent)
                .filter(content -> content != null && !content.isBlank())
                .orElse(null)
            : null;
        return new AgentConversationResponse(
            interviewSessionId,
            session.getAgentSessionId(),
            session.getTitle(),
            session.getGoal(),
            session.getProviderId(),
            session.getRunId(),
            session.getRunStatus(),
            session.getRunStartedAt(),
            session.getRunFinishedAt(),
            session.getRunError(),
            session.getRunStep(),
            session.getRunStepStatus(),
            session.getRunNotice(),
            partialAnswer,
            parseSources(session.getRunSourcesJson()),
            normalized
        );
    }

    public List<AgentHistoryMessage> loadHistory(String interviewSessionId) {
        return loadHistory(interviewSessionId, null);
    }

    public List<AgentHistoryMessage> loadHistory(String interviewSessionId, String agentSessionId) {
        AgentConversationResponse conversation = getConversation(interviewSessionId, agentSessionId);
        List<AgentHistoryMessage> messages = conversation.messages();
        if (conversation.runStatus() != AgentRunStatus.FAILED
            && conversation.runStatus() != AgentRunStatus.CANCELLED) {
            return messages;
        }
        return removeDanglingUserMessage(messages);
    }

    /** 失败或取消的任务没有可配对的 assistant 回复，不把悬空问题再次送入下一轮模型上下文。 */
    private List<AgentHistoryMessage> removeDanglingUserMessage(List<AgentHistoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        AgentHistoryMessage last = messages.getLast();
        if (!"user".equals(last.role())) {
            return messages;
        }
        return List.copyOf(messages.subList(0, messages.size() - 1));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String serializeSources(List<AgentSource> sources) {
        try {
            List<AgentSource> safeSources = sources == null ? List.of() : sources;
            for (int count = safeSources.size(); count >= 0; count--) {
                String serialized = objectMapper.writeValueAsString(safeSources.subList(0, count));
                if (serialized.length() <= MAX_RUN_SOURCES_JSON_CHARS) {
                    return serialized;
                }
            }
        } catch (RuntimeException error) {
            log.warn("Agent 运行来源持久化失败", error);
        }
        return "[]";
    }

    private List<AgentSource> parseSources(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        try {
            List<AgentSource> sources = objectMapper.readValue(
                serialized,
                new TypeReference<List<AgentSource>>() {}
            );
            return sources == null ? List.of() : List.copyOf(sources);
        } catch (RuntimeException error) {
            log.warn("Agent 运行来源恢复失败", error);
            return List.of();
        }
    }

    private Optional<AgentChatSessionEntity> findSession(
        String interviewSessionId, String agentSessionId
    ) {
        if (agentSessionId != null && !agentSessionId.isBlank()) {
            return sessionRepository.findByAgentSessionId(agentSessionId)
                .filter(session -> interviewSessionId.equals(session.getInterviewSessionId()));
        }
        Optional<AgentChatSessionEntity> oldest = sessionRepository
            .findFirstByInterviewSessionIdOrderByCreatedAtAsc(interviewSessionId);
        if (oldest != null && oldest.isPresent()) {
            return oldest;
        }
        // 兼容尚未更新测试替身或旧嵌入式调用方的 Repository 实现。
        Optional<AgentChatSessionEntity> legacy = sessionRepository
            .findByInterviewSessionId(interviewSessionId);
        return legacy == null ? Optional.empty() : legacy;
    }

    private Optional<AgentChatSessionEntity> findSessionForUpdate(
        String interviewSessionId, String agentSessionId
    ) {
        if (agentSessionId != null && !agentSessionId.isBlank()) {
            return sessionRepository.findByAgentSessionIdForUpdate(agentSessionId)
                .filter(session -> interviewSessionId.equals(session.getInterviewSessionId()));
        }
        return sessionRepository.findByInterviewSessionIdForUpdate(interviewSessionId);
    }

    private void ensureSessionIdentity(AgentChatSessionEntity session, String interviewSessionId) {
        if (session.getInterviewSessionId() == null) {
            session.setInterviewSessionId(interviewSessionId);
        }
        if (session.getAgentSessionId() == null || session.getAgentSessionId().isBlank()) {
            session.setAgentSessionId(UUID.randomUUID().toString());
        }
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle("默认 Agent 对话");
        }
    }

    private AgentConversationSummary toSummary(AgentChatSessionEntity session) {
        ensureSessionIdentity(session, session.getInterviewSessionId());
        return new AgentConversationSummary(
            session.getAgentSessionId(),
            session.getInterviewSessionId(),
            session.getTitle(),
            session.getRunStatus(),
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
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
        session.setRunStepStatus(status == AgentRunStatus.COMPLETED ? "completed"
            : status == AgentRunStatus.CANCELLED ? "cancelled" : "failed");
        session.setRunNotice(null);
        sessionRepository.save(session);
    }

    /** 锁住会话后再读取运行状态，避免异步进度落在终态之后。 */
    private AgentChatSessionEntity lockActiveSession(Long messageId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId)
            .map(AgentChatMessageEntity::getSession)
            .flatMap(this::findSessionForUpdateByIdentity)
            .filter(session -> session.getRunStatus() == AgentRunStatus.RUNNING)
            .filter(session -> messageId.equals(session.getActiveMessageId()))
            .orElse(null);
    }

    /**
     * 终态更新与进度更新共享同一把会话级数据库锁。
     * 第二个并发终态拿到锁后会发现状态已不是 RUNNING，从而安全忽略。
     */
    private AgentChatMessageEntity lockActiveMessage(Long messageId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId)
            .flatMap(message -> findSessionForUpdateByIdentity(message.getSession())
                .map(lockedSession -> {
                    message.setSession(lockedSession);
                    return message;
                }))
            .filter(this::isActiveMessage)
            .orElse(null);
    }

    private Optional<AgentChatSessionEntity> findSessionForUpdateByIdentity(
        AgentChatSessionEntity session
    ) {
        if (session == null) {
            return Optional.empty();
        }
        if (session.getAgentSessionId() != null && !session.getAgentSessionId().isBlank()) {
            return sessionRepository.findByAgentSessionIdForUpdate(session.getAgentSessionId());
        }
        return sessionRepository.findByInterviewSessionIdForUpdate(session.getInterviewSessionId());
    }

    private void updateTerminalMessage(
        AgentChatMessageEntity message, String content, boolean completed, String error
    ) {
        message.setContent(content == null ? "" : content);
        message.setCompleted(completed);
        message.setErrorMessage(error);
        messageRepository.save(message);
    }

    private boolean isActiveMessage(AgentChatMessageEntity message) {
        AgentChatSessionEntity session = message.getSession();
        return session != null
            && message.getId() != null
            && message.getId().equals(session.getActiveMessageId())
            && session.getRunStatus() == AgentRunStatus.RUNNING;
    }

    private boolean shouldExposePartialAnswer(AgentRunStatus status) {
        return status == AgentRunStatus.RUNNING
            || status == AgentRunStatus.FAILED
            || status == AgentRunStatus.CANCELLED;
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

    public record PreparedMessage(Long assistantMessageId, Long sessionId, String runId, String agentSessionId) {
        public PreparedMessage(Long assistantMessageId, Long sessionId, String runId) {
            this(assistantMessageId, sessionId, runId, null);
        }
    }

    public record RecoveredRun(
        String interviewSessionId,
        String agentSessionId,
        String goal,
        String providerId,
        String memoryKey,
        String userContent,
        Long assistantMessageId,
        String runId
    ) {
        public RecoveredRun(
            String interviewSessionId,
            String goal,
            String providerId,
            String memoryKey,
            String userContent,
            Long assistantMessageId,
            String runId
        ) {
            this(interviewSessionId, null, goal, providerId, memoryKey, userContent, assistantMessageId, runId);
        }
    }
}
