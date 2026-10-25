package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Voice Interview Service
 * 语音面试服务
 * <p>
 * Provides business logic for voice interview session management including:
 * - Session lifecycle management (create, end, retrieve)
 * - Phase transitions and state tracking
 * - Message persistence and conversation history
 * - Redis caching for active sessions
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ResumeRepository resumeRepository;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    private static final int CACHE_TTL_HOURS = 1;
    private static final String DEFAULT_USER_ID = "default";
    private static final Duration PENDING_EVALUATION_REQUEUE_DELAY = Duration.ofMinutes(3);
    private static final Duration PROCESSING_EVALUATION_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Create a new voice interview session
     * 创建新的语音面试会话
     *
     * @param request Session creation request with role type and phase configuration
     * @return SessionResponseDTO with session details and WebSocket URL
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
            ? request.getLlmProvider()
            : null;
        String ownerId = currentOrDefaultOwner();
        validateResumeOwner(request.getResumeId(), ownerId);

        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(ownerId)
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Created voice interview session: {} with template: {}, phase: {}",
                saved.getId(), effectiveSkillId, saved.getCurrentPhase());

        return buildSessionResponse(saved);
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，此方法不会重复操作。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        endSessionIfInProgress(sessionId, null);
    }

    /** WebSocket 连接关闭时使用握手阶段确认过的用户归属。 */
    @Transactional
    public void endSessionIfInProgress(String sessionId, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        endSession(session);
        sendEvaluateTaskAfterCommit(sessionIdLong);
    }

    /**
     * End interview session and update status
     * 结束面试会话并更新状态
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     */
    @Transactional
    public void endSession(String sessionId) {
        endSession(sessionId, null);
    }

    @Transactional
    public void endSession(String sessionId, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);

        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        endSession(session);
        sendEvaluateTaskAfterCommit(sessionIdLong);
    }

    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);

        sessionRepository.save(session);
        invalidateSessionCache(session.getId());

        log.info("Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
                session.getId(), session.getActualDuration());
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId), null);
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID as Long
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        return getSession(sessionId, null);
    }

    /**
     * 根据当前请求或显式的 WebSocket 用户归属读取会话。
     * ownerId 为空时保留后台消费者和兼容模式按 ID 读取的能力。
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId, String ownerId) {
        if (sessionId == null) {
            return null;
        }

        String effectiveOwnerId = AuthUserContext.currentUserId().orElse(normalizeOwner(ownerId));

        // Try cache first
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        VoiceInterviewSessionEntity cached = bucket.get();

        if (cached != null && (effectiveOwnerId == null || effectiveOwnerId.equals(cached.getUserId()))) {
            log.debug("Session {} found in cache", sessionId);
            return cached;
        }

        if (effectiveOwnerId != null) {
            return sessionRepository.findByIdAndUserId(sessionId, effectiveOwnerId).orElse(null);
        }

        // Fallback to database
        return sessionRepository.findById(sessionId).orElse(null);
    }

    /**
     * Start a new interview phase
     * 开始新的面试阶段
     *
     * @param sessionId Session ID (String format)
     * @param phaseStr  Phase as string (INTRO, TECH, PROJECT, HR)
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        startPhase(sessionId, phaseStr, null);
    }

    @Transactional
    public void startPhase(String sessionId, String phaseStr, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            cacheSession(session); // Update cache

            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("Invalid phase string: {}", phaseStr, e);
        }
    }

    /**
     * Get current phase for session
     * 获取会话当前阶段
     *
     * @param sessionId Session ID (String format)
     * @return Current InterviewPhase or null if session not found
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * Save dialogue message (user and AI text) to database
     * 保存对话消息（用户和AI文本）到数据库
     *
     * @param sessionId Session ID (String format)
     * @param userText  User's recognized speech text
     * @param aiText    AI's generated response text
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        saveMessage(sessionId, userText, aiText, null);
    }

    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
            && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(normalizedUserText != null && !answerAttached
                    ? normalizedUserText
                    : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        return messageRepository
            .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                sessionId)
            .map(message -> {
                message.setUserRecognizedText(userText);
                messageRepository.save(message);
                log.debug("Filled answer for voice message: sessionId={}, sequence={}",
                    sessionId, message.getSequenceNum());
                return true;
            })
            .orElse(false);
    }

    /**
     * Get conversation history for a session
     * 获取会话的对话历史记录
     *
     * @param sessionId Session ID (String format)
     * @return List of messages ordered by sequence number
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * 读取会话已持久化的上下文摘要行（message_type=SUMMARY）。无则空 Optional。
     */
    public Optional<VoiceInterviewMessageEntity> loadSummaryRow(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * 持久化上下文摘要行（真实覆盖边界版本，P1-06 起的唯一生产入口）。
     * 在会话行悲观锁内原地更新或创建 SUMMARY 行；锁内校验边界单调不回退。
     * LLM 压缩必须在事务外完成，本方法只做边界写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSummaryRow(String sessionId, String summary, int coveredSequenceNum) {
        Long sessionIdLong = parseSessionId(sessionId);
        sessionRepository.findByIdForUpdate(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        VoiceInterviewMessageEntity row = messageRepository
            .findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
            .orElseGet(() -> VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
                .sequenceNum(-1)
                .build());
        Integer existingBoundary = row.getSummaryCoveredSequenceNum();
        if (existingBoundary != null && coveredSequenceNum < existingBoundary) {
            log.warn("摘要覆盖边界回退被拒绝: sessionId={}, existing={}, incoming={}",
                sessionId, existingBoundary, coveredSequenceNum);
            return;
        }
        row.setAiGeneratedText(summary);
        // 保留负 sequenceNum 排序语义，但不再作为覆盖边界的事实来源
        row.setSequenceNum(Math.min(row.getSequenceNum() != null ? row.getSequenceNum() : -1, -1));
        row.setSummaryCoveredSequenceNum(coveredSequenceNum);
        messageRepository.save(row);
    }

    /**
     * 旧版按轮数编码的摘要持久化（负 sequenceNum 编码 coveredTurns）。
     * 生产路径禁止再调用——会写出新字段为空的行导致迁移反复触发；
     * 仅供数据修复工具与旧测试兼容使用。
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void saveSummaryRowLegacy(String sessionId, String summary, int coveredTurns) {
        Long sessionIdLong = parseSessionId(sessionId);
        sessionRepository.findByIdForUpdate(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        VoiceInterviewMessageEntity row = messageRepository
            .findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
            .orElseGet(() -> VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
                .build());
        row.setAiGeneratedText(summary);
        row.setSequenceNum(-(coveredTurns + 1));
        messageRepository.save(row);
    }

    /**
     * Get conversation history as DTOs (for frontend)
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Pause interview session
     * 暂停面试会话
     *
     * @param sessionId Session ID
     * @param reason Pause reason (user_initiated or timeout)
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        pauseSession(sessionId, reason, null);
    }

    @Transactional
    public void pauseSession(String sessionId, String reason, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());

        sessionRepository.save(session);
        invalidateSessionCache(sessionIdLong);

        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    /**
     * Resume interview session
     * 恢复面试会话
     *
     * @param sessionId Session ID
     * @return SessionResponseDTO with WebSocket URL
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        return resumeSession(sessionId, null);
    }

    @Transactional
    public SessionResponseDTO resumeSession(String sessionId, String ownerId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = getSession(sessionIdLong, ownerId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Session {} resumed with {} messages in conversation history",
            sessionId, countDialogueMessages(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * Get all sessions for a user
     * 获取用户所有会话
     *
     * @param userId User ID (optional, defaults to DEFAULT_USER_ID)
     * @param status Filter by status (optional)
     * @return List of session metadata
     */
    public List<SessionMetaDTO> getAllSessions(String userId, String status) {
        String requestedUserId = userId;
        userId = AuthUserContext.currentUserId().orElseGet(() ->
            requestedUserId != null && !requestedUserId.isBlank() ? requestedUserId.trim() : DEFAULT_USER_ID);

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }

        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(countDialogueMessages(session.getId()))
                .evaluateStatus(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Get session DTO by ID
     * 通过ID获取会话DTO
     *
     * @param sessionId Session ID as Long
     * @return SessionResponseDTO with session details or null if not found
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * Check if session should transition to next phase based on duration and question count
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     *
     * @param session        Current session
     * @param phaseStartTime Time when current phase started
     * @param questionCount  Number of questions asked in current phase
     * @return true if should transition, false otherwise
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // Rule 1: Max duration reached (forced transition)
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("Phase {} reached max duration {} minutes, forcing transition",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // Rule 2: Min questions reached and sufficient information gathered (AI judgment)
        // For MVP, we use a simple heuristic based on question count
        if (questionCount >= config.getMaxQuestions()) {
            log.info("Phase {} reached max questions {}, suggesting transition",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // Rule 3: Suggested duration reached with min questions
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * Get the next enabled phase after current phase
     * 获取当前阶段之后的下一个启用的阶段
     *
     * @param session Current session
     * @return Next InterviewPhase or COMPLETED if no more phases
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        return switch (current) {
            case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH :
                    session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                            session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                    session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                            VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    // ==================== Private Helper Methods ====================

    /**
     * Determine the first phase based on enabled phases
     * 根据启用的阶段确定第一个阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * Get first enabled phase from session
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(String.format("/ws/voice-interview/%d", session.getId()))
                .build();
    }

    /**
     * Get phase configuration from properties
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * Get next sequence number for messages in a session
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) countDialogueMessages(sessionId) + 1;
    }

    private long countDialogueMessages(Long sessionId) {
        return messageRepository.countBySessionIdAndMessageTypeNot(
            sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * Update evaluation status on session entity (shared by Producer/Consumer/Controller)
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                invalidateSessionCache(sessionId);
                log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * Trigger async evaluation for a session (called by Controller)
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        sendEvaluateTaskAfterCommit(sessionId);
    }

    private void sendEvaluateTaskAfterCommit(Long sessionId) {
        Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendTask.run();
            }
        });
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    /**
     * Cache session in Redis
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
        log.debug("Cached session: {}", session.getId());
    }

    /**
     * Invalidate session cache in Redis
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("Invalidated cache for session: {}", sessionId);
    }

    /**
     * Generate Redis cache key for session
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * Parse session ID from String to Long with error handling
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 清理超时会话，重新投递卡住的 PENDING 评估，并结束超时的 PROCESSING 评估。
     * 由 @Scheduled 在 WebSocketHandler 中定时触发。
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("Cleaning up stale IN_PROGRESS session {}, started at {}",
                session.getId(), session.getStartTime());
            endSession(session);
            sendEvaluateTaskAfterCommit(session.getId());
            cleaned++;
        }

        LocalDateTime pendingStaleThreshold = LocalDateTime.now()
            .minus(PENDING_EVALUATION_REQUEUE_DELAY);
        List<VoiceInterviewSessionEntity> pendingEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PENDING, pendingStaleThreshold);

        for (VoiceInterviewSessionEntity session : pendingEvals) {
            log.warn("Requeueing stale PENDING evaluation for session {}, last updated at {}",
                session.getId(), session.getUpdatedAt());
            session.setEvaluateError(null);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
            invalidateSessionCache(session.getId());
            sendEvaluateTaskAfterCommit(session.getId());
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now()
            .minus(PROCESSING_EVALUATION_TIMEOUT);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("Resetting stuck PROCESSING evaluation for session {}", session.getId());
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("评估超时，请重新触发");
            sessionRepository.save(session);
            invalidateSessionCache(session.getId());
            cleaned++;
        }

        return cleaned;
    }

    private String currentOrDefaultOwner() {
        return AuthUserContext.currentUserId().orElse(DEFAULT_USER_ID);
    }

    private String normalizeOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return null;
        }
        return ownerId.trim();
    }

    private void validateResumeOwner(Long resumeId, String ownerId) {
        if (resumeId == null || resumeRepository == null || AuthUserContext.currentUserId().isEmpty()) {
            return;
        }
        if (resumeRepository.findByIdAndOwnerId(resumeId, ownerId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在");
        }
    }
}
