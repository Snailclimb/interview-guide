package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 低敏审计记录器。
 *
 * <p>审计失败不能阻断面试回答、记忆管理或运行终态；字段只允许有限长度的标识和动作，
 * 不接受 Prompt、回答或密钥正文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditService {
    private static final int MAX_OWNER = 64;
    private static final int MAX_SESSION = 64;
    private static final int MAX_RUN = 64;
    private static final int MAX_ACTION = 64;
    private static final int MAX_RESOURCE_TYPE = 32;
    private static final int MAX_RESOURCE_ID = 128;
    private static final int MAX_RESULT = 32;

    private final AgentAuditEventRepository repository;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void recordQuietly(
        String ownerKey,
        String interviewSessionId,
        String agentSessionId,
        String runId,
        String action,
        String resourceType,
        String resourceId,
        String result
    ) {
        try {
            if (action == null || action.isBlank() || resourceType == null || resourceType.isBlank()) {
                return;
            }
            repository.save(AgentAuditEventEntity.builder()
                .ownerKey(clean(ownerKey, MAX_OWNER))
                .interviewSessionId(clean(interviewSessionId, MAX_SESSION))
                .agentSessionId(clean(agentSessionId, MAX_SESSION))
                .runId(clean(runId, MAX_RUN))
                .action(clean(action, MAX_ACTION))
                .resourceType(clean(resourceType, MAX_RESOURCE_TYPE))
                .resourceId(clean(resourceId, MAX_RESOURCE_ID))
                .result(clean(result, MAX_RESULT))
                .createdAt(LocalDateTime.now())
                .build());
        } catch (RuntimeException error) {
            log.warn("Agent 审计事件写入失败: action={}, resourceType={}", action, resourceType, error);
        }
    }

    private String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ")
            .replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized.substring(0, Math.min(max, normalized.length()));
    }
}
