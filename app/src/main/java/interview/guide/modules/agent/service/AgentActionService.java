package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 安全动作服务。
 *
 * <p>第一批只支持训练计划和练习题草稿：确认动作的结果仍然保存在动作草稿区，不修改任何
 * 原始面试或简历数据。后续真正的业务写工具必须复用这里的 owner、确认、幂等和审计边界。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentActionService {
    private static final String TRAINING_PLAN_TOOL = "interview.plan.draft";
    private static final String PRACTICE_QUESTIONS_TOOL = "practice.questions.draft";
    private static final String DRAFT_RISK = "DRAFT_WRITE";
    private static final String DRAFT_TITLE = "训练计划草稿";
    private static final String DRAFT_PREVIEW = "保存一份独立训练计划草稿，不修改原始面试报告、简历或知识库。";
    private static final String PRACTICE_TITLE = "练习题草稿";
    private static final String PRACTICE_PREVIEW = "保存一组独立练习题草稿，不修改原始面试报告、简历或知识库。";
    private static final int MAX_OWNER_KEY = 64;
    private static final int MAX_IDEMPOTENCY_KEY = 128;
    private static final int MAX_CONTENT = 12_000;

    private final AgentActionRequestRepository repository;
    private final AgentAuditService auditService;

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
        AgentActionRequestEntity saved = repository.save(entity);
        auditService.recordQuietly(
            owner, interviewId, agentId, null, "ACTION_PROPOSED", "agent-action",
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
        if (entity.getStatus() == AgentActionStatus.CANCELLED
            || entity.getStatus() == AgentActionStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已结束，不能重复确认");
        }

        if (entity.getStatus() == AgentActionStatus.PROPOSED) {
            entity.setStatus(AgentActionStatus.CONFIRMED);
            entity.setConfirmedAt(LocalDateTime.now());
            audit(entity, "ACTION_CONFIRMED", AgentActionStatus.CONFIRMED.name());
        }
        entity.setStatus(AgentActionStatus.RUNNING);
        audit(entity, "ACTION_RUNNING", AgentActionStatus.RUNNING.name());

        // 当前动作的“执行”就是将草稿保存到独立动作区；不触碰原始业务实体。
        entity.setStatus(AgentActionStatus.SUCCEEDED);
        entity.setResultSummary(successSummary(entity.getToolId()));
        entity.setCompletedAt(LocalDateTime.now());
        repository.save(entity);
        audit(entity, "ACTION_SUCCEEDED", AgentActionStatus.SUCCEEDED.name());
        return AgentActionResponse.from(entity);
    }

    @Transactional
    public AgentActionResponse updateDraft(String actionId, String ownerKey, String content) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() != AgentActionStatus.PROPOSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有待确认的 Agent 草稿可以编辑");
        }
        entity.setDraftContent(normalizeContent(content));
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
        return "训练计划草稿已保存，原始面试资料未被修改。";
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
        String normalized = requireText(value, "训练计划草稿不能为空", MAX_CONTENT);
        return normalized;
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
