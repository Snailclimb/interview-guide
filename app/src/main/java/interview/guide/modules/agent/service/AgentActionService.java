package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent 安全动作服务。
 *
 * <p>动作统一经过提出、编辑、确认、执行和审计。训练计划/练习题只保存独立草稿，
 * 长期记忆和面试名称使用各自的受控服务执行；后续业务写工具必须复用这里的 owner、确认、
 * 幂等和审计边界。</p>
 */
@Service
public class AgentActionService {
    private static final String TRAINING_PLAN_TOOL = "interview.plan.draft";
    private static final String PRACTICE_QUESTIONS_TOOL = "practice.questions.draft";
    private static final String MEMORY_TOOL = "agent.memory.save";
    private static final String INTERVIEW_TITLE_TOOL = "interview.title.rename";
    private static final String DRAFT_RISK = "DRAFT_WRITE";
    private static final String MEMORY_RISK = "MEMORY_WRITE";
    private static final String TITLE_RISK = "BUSINESS_WRITE";
    private static final String DRAFT_TITLE = "训练计划草稿";
    private static final String DRAFT_PREVIEW = "保存一份独立训练计划草稿，不修改原始面试报告、简历或知识库。";
    private static final String PRACTICE_TITLE = "练习题草稿";
    private static final String PRACTICE_PREVIEW = "保存一组独立练习题草稿，不修改原始面试报告、简历或知识库。";
    private static final String MEMORY_TITLE = "长期记忆草稿";
    private static final String MEMORY_PREVIEW = "确认后写入一条结构化长期记忆，支持后续面试参考，可在记忆区编辑或删除。";
    private static final String TITLE_TITLE = "面试记录名称修改";
    private static final String TITLE_PREVIEW = "确认后只修改这场面试的显示名称，不修改问题、回答、评分或简历。";
    private static final int MAX_OWNER_KEY = 64;
    private static final int MAX_IDEMPOTENCY_KEY = 128;
    private static final int MAX_CONTENT = 12_000;
    private static final int MAX_MEMORY_CONTENT = 160;
    private static final int MAX_TITLE = 120;
    private static final int MAX_PRACTICE_CONTEXT = 6_000;
    private static final int MAX_EXECUTION_ATTEMPTS = 3;

    private final AgentActionRequestRepository repository;
    private final AgentAuditService auditService;
    private final AgentMemoryService memoryService;
    private final InterviewPersistenceService interviewPersistenceService;

    /** 保留轻量构造方式，便于只测试草稿状态机的调用方。 */
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService
    ) {
        this(repository, auditService, null, null);
    }

    @Autowired
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.memoryService = memoryService;
        this.interviewPersistenceService = interviewPersistenceService;
    }

    @Transactional
    public AgentActionResponse proposeTrainingPlanDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey, content, requestedIdempotencyKey,
            TRAINING_PLAN_TOOL, DRAFT_TITLE, DRAFT_PREVIEW
        );
    }

    @Transactional
    public AgentActionResponse proposePracticeQuestionsDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey, content, requestedIdempotencyKey,
            PRACTICE_QUESTIONS_TOOL, PRACTICE_TITLE, PRACTICE_PREVIEW
        );
    }

    @Transactional
    public AgentActionResponse proposeMemoryDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String category,
        String jobScope,
        String requestedIdempotencyKey
    ) {
        String normalizedCategory = normalizeMemoryCategory(category);
        String normalizedScope = normalizeOptional(jobScope, 64);
        String memory = requireText(content, "记忆草稿不能为空", MAX_MEMORY_CONTENT);
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + MEMORY_TOOL + "\n" + normalizedCategory + "\n" + normalizedScope + "\n" + memory);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return AgentActionResponse.from(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(MEMORY_TOOL)
            .riskLevel(MEMORY_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(MEMORY_TITLE)
            .previewText(MEMORY_PREVIEW)
            .draftContent(memory)
            .memoryCategory(normalizedCategory)
            .memoryJobScope(normalizedScope)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    @Transactional
    public AgentActionResponse proposeInterviewTitleDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String title,
        String requestedIdempotencyKey
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String normalizedTitle = requireText(title, "面试记录名称不能为空", MAX_TITLE);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + INTERVIEW_TITLE_TOOL + "\n" + normalizedTitle);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return AgentActionResponse.from(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(INTERVIEW_TITLE_TOOL)
            .riskLevel(TITLE_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(TITLE_TITLE)
            .previewText(TITLE_PREVIEW)
            .draftContent(normalizedTitle)
            .targetTitle(normalizedTitle)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    private AgentActionResponse proposeDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey,
        String toolId,
        String title,
        String preview
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String draft = normalizeContent(content);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId + "\n" + draft);
        }

        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return AgentActionResponse.from(existing);
        }

        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(toolId)
            .riskLevel(DRAFT_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(title)
            .previewText(preview)
            .draftContent(draft)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    private AgentActionResponse saveProposed(AgentActionRequestEntity entity) {
        AgentActionRequestEntity saved = repository.save(entity);
        auditService.recordQuietly(
            saved.getOwnerKey(), saved.getInterviewSessionId(), saved.getAgentSessionId(), null,
            "ACTION_PROPOSED", "agent-action",
            saved.getActionId(), AgentActionStatus.PROPOSED.name()
        );
        return AgentActionResponse.from(saved);
    }

    @Transactional
    public List<AgentActionResponse> listForOwner(String interviewSessionId, String ownerKey) {
        return listForOwner(interviewSessionId, null, ownerKey);
    }

    @Transactional
    public List<AgentActionResponse> listForOwner(
        String interviewSessionId, String agentSessionId, String ownerKey
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = agentSessionId == null ? "" : agentSessionId.trim();
        List<AgentActionRequestEntity> actions = agentId.isBlank()
            ? repository.findTop50ByOwnerKeyAndInterviewSessionIdOrderByCreatedAtDesc(owner, interviewId)
            : repository.findTop50ByOwnerKeyAndInterviewSessionIdAndAgentSessionIdOrderByCreatedAtDesc(
                owner, interviewId, requireText(agentId, "Agent 会话不能为空", 64));
        return actions
            .stream().map(AgentActionResponse::from).toList();
    }

    @Transactional
    public AgentActionResponse confirm(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            return AgentActionResponse.from(entity);
        }
        if (entity.getStatus() == AgentActionStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已取消，不能重复确认");
        }
        if (entity.getStatus() == AgentActionStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作执行失败，请使用重试");
        }

        if (entity.getStatus() == AgentActionStatus.PROPOSED) {
            entity.setStatus(AgentActionStatus.CONFIRMED);
            entity.setConfirmedAt(LocalDateTime.now());
            audit(entity, "ACTION_CONFIRMED", AgentActionStatus.CONFIRMED.name());
        }
        return executeConfirmed(entity, false);
    }

    /** 重试一次已经失败的动作，最多允许三次实际执行。 */
    @Transactional
    public AgentActionResponse retry(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            return AgentActionResponse.from(entity);
        }
        if (entity.getStatus() != AgentActionStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有执行失败的 Agent 动作可以重试");
        }
        if (executionAttempts(entity) >= MAX_EXECUTION_ATTEMPTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已达到最大重试次数，请重新生成草稿");
        }
        return executeConfirmed(entity, true);
    }

    /** 读取当前用户已经确认的练习题草稿，供下一轮面试出题使用。 */
    @Transactional
    public String loadConfirmedPracticeContext(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        AgentActionRequestEntity entity = repository.findByActionId(requireText(
            actionId, "Agent 练习动作不能为空", 64
        )).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 练习动作不存在"));
        if (!owner.equals(entity.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 练习动作");
        }
        if (!PRACTICE_QUESTIONS_TOOL.equals(entity.getToolId())
            || entity.getStatus() != AgentActionStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有已确认的练习题草稿可以用于专项面试");
        }
        String content = normalizeContent(entity.getDraftContent());
        return content.substring(0, Math.min(MAX_PRACTICE_CONTEXT, content.length()));
    }

    private AgentActionResponse executeConfirmed(AgentActionRequestEntity entity, boolean retry) {
        if (executionAttempts(entity) >= MAX_EXECUTION_ATTEMPTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已达到最大重试次数，请重新生成草稿");
        }
        if (retry) {
            audit(entity, "ACTION_RETRY", String.valueOf(executionAttempts(entity) + 1));
        }
        entity.setExecutionAttempts(executionAttempts(entity) + 1);
        entity.setStatus(AgentActionStatus.RUNNING);
        repository.save(entity);
        audit(entity, "ACTION_RUNNING", AgentActionStatus.RUNNING.name());

        try {
            execute(entity);
            entity.setStatus(AgentActionStatus.SUCCEEDED);
            entity.setResultSummary(successSummary(entity.getToolId()));
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
            audit(entity, "ACTION_SUCCEEDED", AgentActionStatus.SUCCEEDED.name());
            return AgentActionResponse.from(entity);
        } catch (RuntimeException error) {
            entity.setStatus(AgentActionStatus.FAILED);
            entity.setResultSummary(failureSummary(entity));
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
            audit(entity, "ACTION_FAILED", AgentActionStatus.FAILED.name());
            return AgentActionResponse.from(entity);
        }
    }

    private void execute(AgentActionRequestEntity entity) {
        if (MEMORY_TOOL.equals(entity.getToolId())) {
            if (memoryService == null) {
                throw new IllegalStateException("长期记忆服务未配置");
            }
            memoryService.rememberItem(
                entity.getOwnerKey(), entity.getMemoryCategory(), entity.getDraftContent(),
                entity.getMemoryJobScope()
            );
            return;
        }
        if (INTERVIEW_TITLE_TOOL.equals(entity.getToolId())) {
            if (interviewPersistenceService == null) {
                throw new IllegalStateException("面试持久化服务未配置");
            }
            interviewPersistenceService.renameSessionForOwner(
                entity.getInterviewSessionId(), entity.getOwnerKey(), entity.getTargetTitle()
            );
        }
        // 训练计划和练习题的确认结果仍然只保存在动作表，不修改其他业务实体。
    }

    @Transactional
    public AgentActionResponse updateDraft(String actionId, String ownerKey, String content) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() != AgentActionStatus.PROPOSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有待确认的 Agent 草稿可以编辑");
        }
        entity.setDraftContent(MEMORY_TOOL.equals(entity.getToolId())
            ? requireText(content, "记忆草稿不能为空", MAX_MEMORY_CONTENT)
            : INTERVIEW_TITLE_TOOL.equals(entity.getToolId())
                ? requireText(content, "面试记录名称不能为空", MAX_TITLE)
                : normalizeContent(content));
        if (INTERVIEW_TITLE_TOOL.equals(entity.getToolId())) {
            entity.setTargetTitle(entity.getDraftContent());
        }
        entity.setResultSummary(null);
        AgentActionRequestEntity saved = repository.save(entity);
        audit(saved, "ACTION_DRAFT_UPDATED", AgentActionStatus.PROPOSED.name());
        return AgentActionResponse.from(saved);
    }

    @Transactional
    public AgentActionResponse cancel(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已保存的 Agent 草稿不能取消");
        }
        if (entity.getStatus() == AgentActionStatus.CANCELLED) {
            return AgentActionResponse.from(entity);
        }
        entity.setStatus(AgentActionStatus.CANCELLED);
        entity.setResultSummary("用户取消了该动作，原始面试资料未被修改。");
        repository.save(entity);
        audit(entity, "ACTION_CANCELLED", AgentActionStatus.CANCELLED.name());
        return AgentActionResponse.from(entity);
    }

    private AgentActionRequestEntity findOwnedForUpdate(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        String safeActionId = requireText(actionId, "Agent 动作标识不能为空", 64);
        AgentActionRequestEntity entity = repository.findByActionIdForUpdate(safeActionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 动作不存在"));
        if (!owner.equals(entity.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 动作");
        }
        return entity;
    }

    private void audit(AgentActionRequestEntity entity, String action, String result) {
        auditService.recordQuietly(
            entity.getOwnerKey(), entity.getInterviewSessionId(), entity.getAgentSessionId(),
            null, action, "agent-action", entity.getActionId(), result
        );
    }

    private String successSummary(String toolId) {
        if (PRACTICE_QUESTIONS_TOOL.equals(toolId)) {
            return "练习题草稿已保存，原始面试资料未被修改。";
        }
        if (MEMORY_TOOL.equals(toolId)) {
            return "长期记忆已保存，可在记忆区编辑或删除。";
        }
        if (INTERVIEW_TITLE_TOOL.equals(toolId)) {
            return "面试记录名称已更新，问题、回答、评分和简历未被修改。";
        }
        return "训练计划草稿已保存，原始面试资料未被修改。";
    }

    private String failureSummary(AgentActionRequestEntity entity) {
        if (executionAttempts(entity) < MAX_EXECUTION_ATTEMPTS) {
            return "第" + executionAttempts(entity) + "次执行失败，原始资料未被修改，可以重试。";
        }
        return "动作执行失败，原始资料未被修改，已达到最大重试次数，请重新生成草稿。";
    }

    private int executionAttempts(AgentActionRequestEntity entity) {
        return entity.getExecutionAttempts() == null ? 0 : entity.getExecutionAttempts();
    }

    private String requireOwner(String value) {
        return requireText(value, "Agent 身份已失效，请刷新页面后重试", MAX_OWNER_KEY);
    }

    private String requireText(String value, String message, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 动作参数过长");
        }
        return normalized;
    }

    private String normalizeContent(String value) {
        String normalized = requireText(value, "草稿不能为空", MAX_CONTENT);
        return normalized;
    }

    private String normalizeMemoryCategory(String value) {
        String normalized = requireText(value, "记忆分类不能为空", 32);
        if (!"weakness".equals(normalized) && !"goal".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆分类不合法");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "Agent 动作参数过长", maxLength);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "幂等键不合法", MAX_IDEMPOTENCY_KEY);
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法创建 Agent 动作幂等键", error);
        }
    }
}
