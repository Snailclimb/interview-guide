package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentTrainingTaskEntity;
import interview.guide.modules.agent.model.AgentTrainingTaskResponse;
import interview.guide.modules.agent.model.AgentTrainingTaskStatus;
import interview.guide.modules.agent.repository.AgentTrainingTaskRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 训练任务的受控写入口。任务与面试报告分离，支持用户自己推进状态。 */
@Service
public class AgentTrainingTaskService {
    private static final int MAX_TITLE = 160;
    private static final int MAX_DESCRIPTION = 12_000;

    private final AgentTrainingTaskRepository repository;
    private final AgentAuditService auditService;

    public AgentTrainingTaskService(
        AgentTrainingTaskRepository repository,
        AgentAuditService auditService
    ) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public AgentTrainingTaskResponse createFromAction(AgentActionRequestEntity action) {
        return repository.findBySourceActionId(action.getActionId())
            .map(AgentTrainingTaskResponse::from)
            .orElseGet(() -> {
                String description = requireText(action.getDraftContent(), "训练任务内容不能为空", MAX_DESCRIPTION);
                AgentTrainingTaskEntity task = AgentTrainingTaskEntity.builder()
                    .taskId(UUID.randomUUID().toString())
                    .ownerKey(action.getOwnerKey())
                    .sourceInterviewSessionId(action.getInterviewSessionId())
                    .sourceAgentSessionId(action.getAgentSessionId())
                    .sourceActionId(action.getActionId())
                    .title(deriveTitle(description))
                    .description(description)
                    .status(AgentTrainingTaskStatus.TODO)
                    .build();
                AgentTrainingTaskEntity saved = repository.save(task);
                auditService.recordQuietly(
                    saved.getOwnerKey(), saved.getSourceInterviewSessionId(), saved.getSourceAgentSessionId(),
                    null, "TRAINING_TASK_CREATED", "agent-training-task", saved.getTaskId(), saved.getStatus().name()
                );
                return AgentTrainingTaskResponse.from(saved);
            });
    }

    @Transactional
    public List<AgentTrainingTaskResponse> listForOwner(String ownerKey, AgentTrainingTaskStatus status) {
        String owner = requireText(ownerKey, "Agent 身份已失效，请刷新页面后重试", 64);
        List<AgentTrainingTaskEntity> tasks = status == null
            ? repository.findTop100ByOwnerKeyOrderByCreatedAtDesc(owner)
            : repository.findTop100ByOwnerKeyAndStatusOrderByCreatedAtDesc(owner, status);
        return tasks.stream().map(AgentTrainingTaskResponse::from).toList();
    }

    @Transactional
    public AgentTrainingTaskResponse updateStatus(String taskId, String ownerKey, AgentTrainingTaskStatus status) {
        String owner = requireText(ownerKey, "Agent 身份已失效，请刷新页面后重试", 64);
        String id = requireText(taskId, "训练任务标识不能为空", 64);
        if (status == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "训练任务状态不能为空");
        }
        AgentTrainingTaskEntity task = repository.findByTaskId(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "训练任务不存在"));
        if (!owner.equals(task.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该训练任务");
        }
        if (task.getStatus() == AgentTrainingTaskStatus.CANCELLED
            || task.getStatus() == AgentTrainingTaskStatus.COMPLETED) {
            if (task.getStatus() == status) {
                return AgentTrainingTaskResponse.from(task);
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已结束的训练任务不能再次修改");
        }
        if (status == AgentTrainingTaskStatus.TODO
            && task.getStatus() != AgentTrainingTaskStatus.TODO) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "进行中的训练任务不能退回待开始");
        }
        if (status == AgentTrainingTaskStatus.COMPLETED
            && task.getStatus() == AgentTrainingTaskStatus.TODO) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "训练任务必须先开始，不能直接标记完成");
        }
        if (status == AgentTrainingTaskStatus.COMPLETED) {
            task.setCompletedAt(LocalDateTime.now());
        }
        task.setStatus(status);
        AgentTrainingTaskEntity saved = repository.save(task);
        auditService.recordQuietly(
            saved.getOwnerKey(), saved.getSourceInterviewSessionId(), saved.getSourceAgentSessionId(),
            null, "TRAINING_TASK_STATUS_UPDATED", "agent-training-task", saved.getTaskId(), saved.getStatus().name()
        );
        return AgentTrainingTaskResponse.from(saved);
    }

    private String deriveTitle(String description) {
        String firstLine = description.lines()
            .map(line -> line.replaceFirst("^\\s*#+\\s*", "").replaceFirst("^\\s*[-*]\\s*", "").trim())
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("完成一项面试训练");
        return firstLine.substring(0, Math.min(MAX_TITLE, firstLine.length()));
    }

    private String requireText(String value, String message, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "训练任务内容过长");
        }
        return normalized;
    }
}
