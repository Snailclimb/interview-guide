package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentConversationSummary;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentLoopTraceItem;
import interview.guide.modules.agent.model.AgentRunUsage;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.agent.repository.AgentConversationCreationRequestRepository;
import interview.guide.modules.agent.model.AgentConversationCreationRequestEntity;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.auth.AuthUserContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AgentConversationService {

    private static final int MAX_PERSISTED_HISTORY_MESSAGES = 64;
    private static final int STALE_RUN_MINUTES = 15;
    private static final int MAX_RUN_SOURCES_JSON_CHARS = 12_000;
    private static final int MAX_LOOP_TRACE_JSON_CHARS = 24_000;
    private static final int MAX_LOOP_TRACE_ITEMS = 128;
    private static final int MAX_OWNER_KEY_LENGTH = 64;

    private final AgentChatSessionRepository sessionRepository;
    private final AgentChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final InterviewPersistenceService interviewPersistenceService;
    private final AgentAuditService auditService;
    private final AgentUsageService usageService;
    private final AgentConversationCreationRequestRepository creationRequestRepository;

    /** 兼容无 Spring 容器的旧测试和内部调用方。 */
    public AgentConversationService(
        AgentChatSessionRepository sessionRepository,
        AgentChatMessageRepository messageRepository,
        ObjectMapper objectMapper,
        InterviewPersistenceService interviewPersistenceService
    ) {
        this(sessionRepository, messageRepository, objectMapper, interviewPersistenceService, null, null, null);
    }

    @Autowired
    public AgentConversationService(
        AgentChatSessionRepository sessionRepository,
        AgentChatMessageRepository messageRepository,
        ObjectMapper objectMapper,
        InterviewPersistenceService interviewPersistenceService,
        AgentAuditService auditService,
        AgentUsageService usageService,
        AgentConversationCreationRequestRepository creationRequestRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.interviewPersistenceService = interviewPersistenceService;
        this.auditService = auditService;
        this.usageService = usageService;
        this.creationRequestRepository = creationRequestRepository;
    }

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
        String ownerKey = normalizeOwnerKey(memoryKey, false);
        assertAccess(interviewSessionId, agentSessionId, ownerKey);
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .filter(candidate -> ownerKey == null || isOwnedBy(candidate, ownerKey))
            .orElseGet(() -> AgentChatSessionEntity.builder()
                .interviewSessionId(interviewSessionId)
                .agentSessionId(UUID.randomUUID().toString())
                .title("新 Agent 对话")
                .ownerKey(ownerKey)
                .build());
        ensureSessionIdentity(session, interviewSessionId);
        if (ownerKey != null) {
            session.setOwnerKey(ownerKey);
        }
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
        session.setLoopPlan(null);
        session.setLoopNextStep(0);
        session.setLoopCompletedSteps(null);
        session.setLoopTraceJson(null);
        clearRunUsage(session);
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
        audit(session, "RUN_STARTED", "agent-run", runId, "success");
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
        return createConversation(interviewSessionId, title, null, null);
    }

    @Transactional
    public AgentConversationSummary createConversation(
        String interviewSessionId, String title, String ownerKey
    ) {
        return createConversation(interviewSessionId, title, ownerKey, null);
    }

    @Transactional
    public AgentConversationSummary createConversation(
        String interviewSessionId, String title, String ownerKey, String requestedIdempotencyKey
    ) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, false);
        assertInterviewAccess(interviewSessionId, normalizedOwnerKey);
        String normalizedTitle = title == null || title.isBlank() ? "新 Agent 对话" : truncate(title.trim(), 120);
        String idempotencyKey = normalizeCreationIdempotencyKey(requestedIdempotencyKey);
        String agentSessionId = UUID.randomUUID().toString();

        if (creationRequestRepository != null && normalizedOwnerKey != null && idempotencyKey != null) {
            int claimed = creationRequestRepository.tryClaim(
                normalizedOwnerKey, interviewSessionId, idempotencyKey, agentSessionId
            );
            if (claimed == 0) {
                AgentConversationCreationRequestEntity existingRequest = creationRequestRepository
                    .findByOwnerKeyAndIdempotencyKey(normalizedOwnerKey, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Agent 创建请求幂等记录未找到"));
                if (!interviewSessionId.equals(existingRequest.getInterviewSessionId())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 创建请求标识已用于其他面试");
                }
                return sessionRepository.findByAgentSessionId(existingRequest.getAgentSessionId())
                    .filter(existing -> isOwnedBy(existing, normalizedOwnerKey))
                    .map(this::toSummary)
                    .orElseThrow(() -> new IllegalStateException("Agent 创建请求对应的会话不存在"));
            }
        }

        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId(interviewSessionId)
            .agentSessionId(agentSessionId)
            .title(normalizedTitle)
            .ownerKey(normalizedOwnerKey)
            .memoryKey(normalizedOwnerKey)
            .build();
        AgentChatSessionEntity saved = sessionRepository.save(session);
        audit(saved, "CONVERSATION_CREATED", "agent-session", saved.getAgentSessionId(), "success");
        return toSummary(saved);
    }

    @Transactional
    public List<AgentConversationSummary> listConversations(String interviewSessionId) {
        return listConversations(interviewSessionId, null);
    }

    @Transactional
    public List<AgentConversationSummary> listConversations(String interviewSessionId, String ownerKey) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, false);
        assertInterviewAccess(interviewSessionId, normalizedOwnerKey);
        return sessionRepository.findByInterviewSessionIdOrderByUpdatedAtDesc(interviewSessionId).stream()
            .filter(session -> !session.isArchived())
            .filter(session -> normalizedOwnerKey == null || isOwnedBy(session, normalizedOwnerKey))
            .map(this::toSummary)
            .toList();
    }

    @Transactional
    public AgentConversationSummary renameConversation(
        String interviewSessionId, String agentSessionId, String title, String ownerKey
    ) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, true);
        assertAccess(interviewSessionId, agentSessionId, normalizedOwnerKey);
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .filter(candidate -> isOwnedBy(candidate, normalizedOwnerKey))
            .orElseThrow(this::accessDenied);
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 会话名称不能为空");
        }
        session.setTitle(truncate(normalizedTitle, 120));
        AgentChatSessionEntity saved = sessionRepository.save(session);
        audit(saved, "CONVERSATION_RENAMED", "agent-session", saved.getAgentSessionId(), "success");
        return toSummary(saved);
    }

    @Transactional
    public void archiveConversation(
        String interviewSessionId, String agentSessionId, String ownerKey
    ) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, true);
        assertAccess(interviewSessionId, agentSessionId, normalizedOwnerKey);
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .filter(candidate -> isOwnedBy(candidate, normalizedOwnerKey))
            .orElseThrow(this::accessDenied);
        if (session.getRunStatus() == AgentRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 正在执行，完成或取消后才能归档");
        }
        session.setArchived(true);
        sessionRepository.save(session);
        audit(session, "CONVERSATION_ARCHIVED", "agent-session", session.getAgentSessionId(), "success");
    }

    /** API 层在读取报告或开始流式任务前调用，避免只凭 sessionId 越权读取 Agent 上下文。 */
    @Transactional
    public void assertAccess(String interviewSessionId, String agentSessionId, String ownerKey) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, false);
        if (normalizedOwnerKey == null) {
            return;
        }
        assertInterviewAccess(interviewSessionId, normalizedOwnerKey);
        Optional<AgentChatSessionEntity> session = findRawSession(interviewSessionId, agentSessionId);
        if (agentSessionId != null && !agentSessionId.isBlank() && session.isEmpty()) {
            throw accessDenied();
        }
        // 未指定线程时允许旧的无 owner 线程自然迁移：后续读取会返回空会话，
        // 前端随后创建当前身份的新线程；指定了 agentSessionId 则必须严格校验。
        if (agentSessionId != null && !agentSessionId.isBlank()) {
            session.ifPresent(candidate -> {
                if (!isOwnedBy(candidate, normalizedOwnerKey)) {
                    throw accessDenied();
                }
            });
        }
    }

    public String requireOwnerKey(String ownerKey) {
        return normalizeOwnerKey(ownerKey, true);
    }

    @Transactional
    public void completeMessage(Long messageId, String content) {
        completeMessage(messageId, content, null);
    }

    @Transactional
    public void completeMessage(Long messageId, String content, String expectedRunId) {
        AgentChatMessageEntity message = lockActiveMessage(messageId, expectedRunId);
        if (message == null) {
            log.info("忽略已失效 Agent 完成事件: messageId={}", messageId);
            return;
        }
        updateTerminalMessage(message, content, true, null);
        markRunFinished(message.getSession(), AgentRunStatus.COMPLETED, null);
    }

    @Transactional
    public void failMessage(Long messageId, String partialContent, String errorMessage) {
        failMessage(messageId, partialContent, errorMessage, null);
    }

    @Transactional
    public void failMessage(
        Long messageId, String partialContent, String errorMessage, String expectedRunId
    ) {
        String safeError = truncate(errorMessage, 500);
        AgentChatMessageEntity message = lockActiveMessage(messageId, expectedRunId);
        if (message == null) {
            log.info("忽略已失效 Agent 失败事件: messageId={}", messageId);
            return;
        }
        updateTerminalMessage(message, partialContent, false, safeError);
        markRunFinished(message.getSession(), AgentRunStatus.FAILED, safeError);
    }

    @Transactional
    public void cancelMessage(Long messageId, String partialContent) {
        cancelMessage(messageId, partialContent, null);
    }

    @Transactional
    public void cancelMessage(Long messageId, String partialContent, String expectedRunId) {
        String error = "客户端中断了本次回答";
        AgentChatMessageEntity message = lockActiveMessage(messageId, expectedRunId);
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
        return cancelRun(interviewSessionId, agentSessionId, null);
    }

    @Transactional
    public boolean cancelRun(String interviewSessionId, String agentSessionId, String ownerKey) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, false);
        AgentChatSessionEntity session = findSessionForUpdate(interviewSessionId, agentSessionId)
            .filter(candidate -> normalizedOwnerKey == null || isOwnedBy(candidate, normalizedOwnerKey))
            .orElse(null);
        if (session == null || session.getRunStatus() != AgentRunStatus.RUNNING) {
            return false;
        }
        if (session.getActiveMessageId() != null) {
            cancelMessage(session.getActiveMessageId(), "用户请求取消本次回答", session.getRunId());
        } else {
            markRunFinished(session, AgentRunStatus.CANCELLED, "用户请求取消本次回答");
        }
        return true;
    }

    /** 持久化当前 Agent 编排阶段，供断线和页面刷新后恢复展示。 */
    @Transactional
    public void updateRunProgress(Long messageId, String step, String status, String notice) {
        updateRunProgress(messageId, step, status, notice, null);
    }

    /** 仅允许指定运行代次写入进度，防止恢复前的旧任务迟到覆盖新任务。 */
    @Transactional
    public void updateRunProgress(
        Long messageId, String step, String status, String notice, String expectedRunId
    ) {
        AgentChatSessionEntity session = lockActiveSession(messageId, expectedRunId);
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
        updateRunSources(messageId, sources, null);
    }

    @Transactional
    public void updateRunSources(Long messageId, List<AgentSource> sources, String expectedRunId) {
        String serialized = serializeSources(sources);
        AgentChatSessionEntity session = lockActiveSession(messageId, expectedRunId);
        if (session == null) {
            log.info("忽略已失效 Agent 来源事件: messageId={}", messageId);
            return;
        }
        session.setRunSourcesJson(serialized);
        sessionRepository.save(session);
    }

    /** 持久化 Loop 计划和下一个待执行步骤，供应用重启后的任务接管使用。 */
    @Transactional
    public void updateLoopState(Long messageId, String plan, int nextStepIndex) {
        updateLoopState(messageId, plan, nextStepIndex, List.of(), null);
    }

    @Transactional
    public void updateLoopState(
        Long messageId, String plan, int nextStepIndex, String expectedRunId
    ) {
        updateLoopState(messageId, plan, nextStepIndex, List.of(), expectedRunId);
    }

    /** 持久化 Loop 计划、下一个步骤以及已完成步骤集合。 */
    @Transactional
    public void updateLoopState(
        Long messageId,
        String plan,
        int nextStepIndex,
        List<Integer> completedStepIndices
    ) {
        updateLoopState(messageId, plan, nextStepIndex, completedStepIndices, null);
    }

    @Transactional
    public void updateLoopState(
        Long messageId,
        String plan,
        int nextStepIndex,
        List<Integer> completedStepIndices,
        String expectedRunId
    ) {
        AgentChatSessionEntity session = lockActiveSession(messageId, expectedRunId);
        if (session == null) {
            log.info("忽略已失效 Agent Loop 状态事件: messageId={}", messageId);
            return;
        }
        session.setLoopPlan(truncate(plan, 1200));
        session.setLoopNextStep(Math.max(0, nextStepIndex));
        session.setLoopCompletedSteps(serializeLoopCompletedSteps(completedStepIndices));
        sessionRepository.save(session);
    }

    /** 持久化结构化 Loop 轨迹，供刷新、断线和应用重启后恢复展示。 */
    @Transactional
    public void updateLoopTrace(
        Long messageId, List<AgentLoopTraceItem> trace, String expectedRunId
    ) {
        AgentChatSessionEntity session = lockActiveSession(messageId, expectedRunId);
        if (session == null) {
            log.info("忽略已失效 Agent Loop 轨迹事件: messageId={}", messageId);
            return;
        }
        session.setLoopTraceJson(serializeLoopTrace(trace));
        sessionRepository.save(session);
    }

    /** 持久化最近一次模型运行的聚合用量，供刷新页面和验收查看。 */
    @Transactional
    public void updateRunUsage(Long messageId, AgentRunUsage usage) {
        updateRunUsage(messageId, usage, null);
    }

    @Transactional
    public void updateRunUsage(Long messageId, AgentRunUsage usage, String expectedRunId) {
        // 用量快照在 completeMessage/failMessage/cancelMessage 之后由 doFinally 写入，
        // 此时会话已经进入终态且 activeMessageId 已清空，不能继续使用只允许 RUNNING
        // 且绑定 activeMessageId 的进度锁，否则最近一次用量虽写不到累计表，累计统计
        // 会永久显示为 0。
        AgentChatSessionEntity session = lockUsageSession(messageId, expectedRunId);
        if (session == null) {
            log.info("忽略已失效 Agent 用量事件: messageId={}", messageId);
            return;
        }
        AgentRunUsage safe = usage == null ? AgentRunUsage.empty() : usage;
        applyRunUsage(session, safe);
        sessionRepository.save(session);
        if (usageService != null && isTerminalRun(session.getRunStatus())) {
            try {
                usageService.record(session, safe);
            } catch (Throwable error) {
                // 累计用量是附加观测能力，不能影响最近一次用量或回答终态落库。
                log.warn("Agent 累计用量持久化失败: messageId={}", messageId, error);
            }
        }
    }

    private AgentChatSessionEntity lockUsageSession(Long messageId, String expectedRunId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId)
            .map(AgentChatMessageEntity::getSession)
            .flatMap(this::findSessionForUpdateByIdentity)
            .filter(session -> expectedRunId == null
                || expectedRunId.equals(session.getRunId()))
            .orElse(null);
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
        clearRunUsage(session);
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
            session.getOwnerKey(),
            session.getMemoryKey(),
            user.getContent(),
            assistant.getId(),
            session.getRunId(),
            session.getLoopPlan(),
            session.getLoopNextStep(),
            parseLoopCompletedSteps(session.getLoopCompletedSteps()),
            parseLoopTrace(session.getLoopTraceJson())
        );
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId) {
        return getConversation(interviewSessionId, null);
    }

    @Transactional
    public AgentConversationResponse getConversation(String interviewSessionId, String agentSessionId) {
        return getConversation(interviewSessionId, agentSessionId, null);
    }

    @Transactional
    public AgentConversationResponse getConversation(
        String interviewSessionId, String agentSessionId, String ownerKey
    ) {
        String normalizedOwnerKey = normalizeOwnerKey(ownerKey, false);
        AgentChatSessionEntity session = findSession(interviewSessionId, agentSessionId)
            .filter(candidate -> normalizedOwnerKey == null || isOwnedBy(candidate, normalizedOwnerKey))
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
            normalized,
            readRunUsage(session),
            session.getLoopPlan(),
            session.getLoopNextStep(),
            parseLoopCompletedSteps(session.getLoopCompletedSteps()),
            parseLoopTrace(session.getLoopTraceJson())
        );
    }

    public List<AgentHistoryMessage> loadHistory(String interviewSessionId) {
        return loadHistory(interviewSessionId, null);
    }

    public List<AgentHistoryMessage> loadHistory(String interviewSessionId, String agentSessionId) {
        return loadHistory(interviewSessionId, agentSessionId, null);
    }

    public List<AgentHistoryMessage> loadHistory(
        String interviewSessionId, String agentSessionId, String ownerKey
    ) {
        AgentConversationResponse conversation = getConversation(interviewSessionId, agentSessionId, ownerKey);
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

    private String serializeLoopCompletedSteps(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        indices.stream()
            .filter(index -> index != null && index >= 0)
            .sorted(Comparator.naturalOrder())
            .limit(128)
            .forEach(normalized::add);
        return normalized.isEmpty()
            ? null
            : normalized.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }

    private List<Integer> parseLoopCompletedSteps(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(serialized.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .flatMap(value -> {
                try {
                    return java.util.stream.Stream.of(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    return java.util.stream.Stream.empty();
                }
            })
            .filter(index -> index >= 0)
            .distinct()
            .sorted()
            .limit(128)
            .toList();
    }

    private String serializeLoopTrace(List<AgentLoopTraceItem> trace) {
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        try {
            List<AgentLoopTraceItem> safeTrace = trace.stream()
                .filter(item -> item != null)
                .limit(MAX_LOOP_TRACE_ITEMS)
                .toList();
            for (int count = safeTrace.size(); count >= 0; count--) {
                String serialized = objectMapper.writeValueAsString(safeTrace.subList(0, count));
                if (serialized.length() <= MAX_LOOP_TRACE_JSON_CHARS) {
                    return serialized;
                }
            }
        } catch (RuntimeException error) {
            log.warn("Agent Loop 轨迹持久化失败", error);
        }
        return "[]";
    }

    private List<AgentLoopTraceItem> parseLoopTrace(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        try {
            List<AgentLoopTraceItem> trace = objectMapper.readValue(
                serialized,
                new TypeReference<List<AgentLoopTraceItem>>() {}
            );
            return trace == null ? List.of() : trace.stream()
                .filter(item -> item != null)
                .limit(MAX_LOOP_TRACE_ITEMS)
                .toList();
        } catch (RuntimeException error) {
            log.warn("Agent Loop 轨迹恢复失败", error);
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

    private Optional<AgentChatSessionEntity> findRawSession(
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
        Optional<AgentChatSessionEntity> legacy = sessionRepository
            .findByInterviewSessionId(interviewSessionId);
        return legacy == null ? Optional.empty() : legacy;
    }

    private boolean isOwnedBy(AgentChatSessionEntity session, String ownerKey) {
        if (session == null || ownerKey == null || ownerKey.isBlank()) {
            return false;
        }
        if (ownerKey.equals(session.getOwnerKey())) {
            return true;
        }
        // 兼容迁移前已存在、但 memory_key 与当前浏览器身份一致的旧线程。
        if ((session.getOwnerKey() == null || session.getOwnerKey().isBlank())
            && ownerKey.equals(session.getMemoryKey())) {
            session.setOwnerKey(ownerKey);
            sessionRepository.save(session);
            return true;
        }
        // 迁移前由服务端内部创建且没有 memory_key 的旧线程，首次带身份访问时完成归属迁移。
        if ((session.getOwnerKey() == null || session.getOwnerKey().isBlank())
            && (session.getMemoryKey() == null || session.getMemoryKey().isBlank())) {
            session.setOwnerKey(ownerKey);
            session.setMemoryKey(ownerKey);
            sessionRepository.save(session);
            return true;
        }
        return false;
    }

    private String normalizeOwnerKey(String ownerKey, boolean required) {
        String normalized = ownerKey == null ? "" : ownerKey.trim();
        if (normalized.isBlank()) {
            if (required) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
            }
            return null;
        }
        if (normalized.length() > MAX_OWNER_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份标识不合法");
        }
        return normalized;
    }

    private String normalizeCreationIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private BusinessException accessDenied() {
        return new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 会话");
    }

    private void assertInterviewAccess(String interviewSessionId, String ownerKey) {
        if (ownerKey != null && AuthUserContext.currentUserId().isPresent()
            && interviewPersistenceService != null) {
            Optional<?> ownedSession = interviewPersistenceService.findBySessionIdForOwnerOrClaimLegacy(
                interviewSessionId, ownerKey);
            if (ownedSession != null && ownedSession.isEmpty()) {
                throw accessDenied();
            }
        }
    }

    private Optional<AgentChatSessionEntity> findSessionForUpdate(
        String interviewSessionId, String agentSessionId
    ) {
        if (agentSessionId != null && !agentSessionId.isBlank()) {
            return sessionRepository.findByAgentSessionIdForUpdate(agentSessionId)
                .filter(session -> interviewSessionId.equals(session.getInterviewSessionId()));
        }
        List<AgentChatSessionEntity> oldest = sessionRepository
            .findTop1ByInterviewSessionIdOrderByCreatedAtAsc(interviewSessionId);
        if (oldest != null && !oldest.isEmpty()) {
            return Optional.of(oldest.getFirst());
        }
        // 兼容旧测试替身和未实现派生锁查询的调用方；生产 Repository 使用上面的
        // FirstBy 查询，确保多线程场景只锁最早线程而不是触发非唯一结果异常。
        Optional<AgentChatSessionEntity> legacy = sessionRepository
            .findByInterviewSessionIdForUpdate(interviewSessionId);
        return legacy == null ? Optional.empty() : legacy;
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
        // 保留本次 Loop 的计划、检查点和轨迹，供历史查看与失败后的恢复诊断；
        // 下一次 prepareMessage 会在新运行开始前清理这些运行态字段。
        sessionRepository.save(session);
        audit(session, "RUN_" + status.name(), "agent-run", session.getRunId(), status.name());
    }

    private void audit(
        AgentChatSessionEntity session,
        String action,
        String resourceType,
        String resourceId,
        String result
    ) {
        if (auditService == null || session == null) {
            return;
        }
        auditService.recordQuietly(
            session.getOwnerKey(), session.getInterviewSessionId(), session.getAgentSessionId(),
            session.getRunId(), action, resourceType, resourceId, result
        );
    }

    private void clearRunUsage(AgentChatSessionEntity session) {
        session.setRunPromptTokens(null);
        session.setRunCompletionTokens(null);
        session.setRunTotalTokens(null);
        session.setRunModelCalls(null);
        session.setRunDurationMillis(null);
        session.setRunCost(null);
        session.setRunCurrency(null);
        session.setRunProvider(null);
        session.setRunModel(null);
    }

    private void applyRunUsage(AgentChatSessionEntity session, AgentRunUsage usage) {
        AgentRunUsage safe = usage == null ? AgentRunUsage.empty() : usage;
        session.setRunPromptTokens(safe.promptTokens());
        session.setRunCompletionTokens(safe.completionTokens());
        session.setRunTotalTokens(safe.totalTokens());
        session.setRunModelCalls(safe.modelCalls());
        session.setRunDurationMillis(safe.durationMillis());
        session.setRunCost(safe.cost());
        session.setRunCurrency(safe.currency());
        session.setRunProvider(safe.provider());
        session.setRunModel(safe.model());
    }

    private boolean isTerminalRun(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
            || status == AgentRunStatus.FAILED
            || status == AgentRunStatus.CANCELLED;
    }

    private AgentRunUsage readRunUsage(AgentChatSessionEntity session) {
        boolean present = session.getRunPromptTokens() != null
            || session.getRunCompletionTokens() != null
            || session.getRunTotalTokens() != null
            || session.getRunModelCalls() != null
            || session.getRunDurationMillis() != null
            || session.getRunCost() != null
            || session.getRunProvider() != null
            || session.getRunModel() != null;
        return present ? new AgentRunUsage(
            value(session.getRunPromptTokens()),
            value(session.getRunCompletionTokens()),
            value(session.getRunTotalTokens()),
            value(session.getRunModelCalls()),
            session.getRunDurationMillis() == null ? 0 : session.getRunDurationMillis(),
            session.getRunCost(),
            session.getRunCurrency(),
            session.getRunProvider(),
            session.getRunModel()
        ) : null;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    /** 锁住会话后再读取运行状态，避免异步进度落在终态之后。 */
    private AgentChatSessionEntity lockActiveSession(Long messageId) {
        return lockActiveSession(messageId, null);
    }

    private AgentChatSessionEntity lockActiveSession(Long messageId, String expectedRunId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId)
            .map(AgentChatMessageEntity::getSession)
            .flatMap(this::findSessionForUpdateByIdentity)
            .filter(session -> session.getRunStatus() == AgentRunStatus.RUNNING)
            .filter(session -> messageId.equals(session.getActiveMessageId()))
            .filter(session -> expectedRunId == null
                || expectedRunId.equals(session.getRunId()))
            .orElse(null);
    }

    /**
     * 终态更新与进度更新共享同一把会话级数据库锁。
     * 第二个并发终态拿到锁后会发现状态已不是 RUNNING，从而安全忽略。
     */
    private AgentChatMessageEntity lockActiveMessage(Long messageId) {
        return lockActiveMessage(messageId, null);
    }

    private AgentChatMessageEntity lockActiveMessage(Long messageId, String expectedRunId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId)
            .flatMap(message -> findSessionForUpdateByIdentity(message.getSession())
                .map(lockedSession -> {
                    message.setSession(lockedSession);
                    return message;
                }))
            .filter(message -> isActiveMessage(message, expectedRunId))
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
        return findSessionForUpdate(session.getInterviewSessionId(), session.getAgentSessionId());
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
        return isActiveMessage(message, null);
    }

    private boolean isActiveMessage(AgentChatMessageEntity message, String expectedRunId) {
        AgentChatSessionEntity session = message.getSession();
        return session != null
            && message.getId() != null
            && message.getId().equals(session.getActiveMessageId())
            && session.getRunStatus() == AgentRunStatus.RUNNING
            && (expectedRunId == null || expectedRunId.equals(session.getRunId()));
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
        String ownerKey,
        String memoryKey,
        String userContent,
        Long assistantMessageId,
        String runId,
        String loopPlan,
        int loopNextStep,
        List<Integer> loopCompletedStepIndices,
        List<AgentLoopTraceItem> loopTrace
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
            this(interviewSessionId, null, goal, providerId, null, memoryKey,
                userContent, assistantMessageId, runId, null, 0, List.of(), List.of());
        }

        public RecoveredRun(
            String interviewSessionId,
            String agentSessionId,
            String goal,
            String providerId,
            String memoryKey,
            String userContent,
            Long assistantMessageId,
            String runId
        ) {
            this(interviewSessionId, agentSessionId, goal, providerId, null, memoryKey,
                userContent, assistantMessageId, runId, null, 0, List.of(), List.of());
        }

        public RecoveredRun {
            loopCompletedStepIndices = loopCompletedStepIndices == null
                ? List.of() : List.copyOf(loopCompletedStepIndices);
            loopTrace = loopTrace == null ? List.of() : List.copyOf(loopTrace);
        }
    }
}
