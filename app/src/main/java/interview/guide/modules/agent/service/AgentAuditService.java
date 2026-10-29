package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.model.AgentAuditEventResponse;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent 低敏审计记录器。
 *
 * <p>审计失败不能阻断面试回答、记忆管理或运行终态；字段只允许有限长度的标识和动作，
 * 不接受 Prompt、回答或密钥正文。</p>
 */
@Slf4j
@Service
public class AgentAuditService {
    private static final int MAX_OWNER = 64;
    private static final int MAX_SESSION = 64;
    private static final int MAX_RUN = 64;
    private static final int MAX_ACTION = 64;
    private static final int MAX_RESOURCE_TYPE = 32;
    private static final int MAX_RESOURCE_ID = 128;
    private static final int MAX_RESULT = 32;
    private static final int MAX_QUERY_LIMIT = 100;

    private final AgentAuditEventRepository repository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final AgentChatSessionRepository agentChatSessionRepository;
    private final TransactionTemplate auditTransactionTemplate;

    /** 保留无 Spring 容器调用方式，便于单元测试和兼容旧调用方。 */
    public AgentAuditService(AgentAuditEventRepository repository) {
        this(repository, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentAuditService(
        AgentAuditEventRepository repository,
        PlatformTransactionManager transactionManager,
        InterviewSessionRepository interviewSessionRepository,
        AgentChatSessionRepository agentChatSessionRepository
    ) {
        this.repository = repository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.agentChatSessionRepository = agentChatSessionRepository;
        if (transactionManager == null) {
            this.auditTransactionTemplate = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.auditTransactionTemplate = template;
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AgentAuditEventResponse> listForOwner(String ownerKey, int limit) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(MAX_QUERY_LIMIT, limit));
        List<AgentAuditEventResponse> events = repository.findTop100ByOwnerKeyOrderByCreatedAtDesc(ownerKey.trim()).stream()
            .limit(safeLimit)
            .map(AgentAuditEventResponse::from)
            .toList();
        if (events.isEmpty() || interviewSessionRepository == null || agentChatSessionRepository == null) {
            return events;
        }

        Set<String> interviewIds = events.stream()
            .map(AgentAuditEventResponse::interviewSessionId)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toSet());
        Set<String> agentSessionIds = events.stream()
            .map(AgentAuditEventResponse::agentSessionId)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toSet());
        Map<String, InterviewSessionEntity> interviews = interviewIds.isEmpty()
            ? Map.of()
            : interviewSessionRepository.findAllBySessionIdInAndOwnerId(interviewIds, ownerKey.trim()).stream()
                .collect(Collectors.toMap(InterviewSessionEntity::getSessionId, Function.identity(), (left, right) -> left));
        Map<String, AgentChatSessionEntity> agentSessions = agentSessionIds.isEmpty()
            ? Map.of()
            : agentChatSessionRepository.findAllByAgentSessionIdInAndOwnerKey(agentSessionIds, ownerKey.trim()).stream()
                .collect(Collectors.toMap(AgentChatSessionEntity::getAgentSessionId, Function.identity(), (left, right) -> left));
        return events.stream()
            .map(event -> event.withSourceNames(
                titleOf(interviews.get(event.interviewSessionId())),
                titleOf(agentSessions.get(event.agentSessionId()))
            ))
            .toList();
    }

    private String titleOf(InterviewSessionEntity session) {
        if (session == null) return null;
        if (session.getTitle() != null && !session.getTitle().isBlank()) return session.getTitle();
        return session.getSkillId();
    }

    private String titleOf(AgentChatSessionEntity session) {
        if (session == null || session.getTitle() == null || session.getTitle().isBlank()) return null;
        return session.getTitle();
    }

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
            AuditRecord record = new AuditRecord(
                clean(ownerKey, MAX_OWNER),
                clean(interviewSessionId, MAX_SESSION),
                clean(agentSessionId, MAX_SESSION),
                clean(runId, MAX_RUN),
                clean(action, MAX_ACTION),
                clean(resourceType, MAX_RESOURCE_TYPE),
                clean(resourceId, MAX_RESOURCE_ID),
                clean(result, MAX_RESULT)
            );
            if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        persist(record);
                    }
                });
                return;
            }
            persist(record);
        } catch (RuntimeException error) {
            log.warn("Agent 审计事件写入失败: action={}, resourceType={}", action, resourceType, error);
        }
    }

    private void persist(AuditRecord record) {
        try {
            if (auditTransactionTemplate == null) {
                repository.save(record.toEntity());
                return;
            }
            auditTransactionTemplate.executeWithoutResult(status -> repository.save(record.toEntity()));
        } catch (RuntimeException error) {
            log.warn(
                "Agent 审计事件写入失败: action={}, resourceType={}",
                record.action(), record.resourceType(), error
            );
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

    private record AuditRecord(
        String ownerKey,
        String interviewSessionId,
        String agentSessionId,
        String runId,
        String action,
        String resourceType,
        String resourceId,
        String result
    ) {
        private AgentAuditEventEntity toEntity() {
            return AgentAuditEventEntity.builder()
                .ownerKey(ownerKey)
                .interviewSessionId(interviewSessionId)
                .agentSessionId(agentSessionId)
                .runId(runId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .result(result)
                .createdAt(LocalDateTime.now())
                .build();
        }
    }
}
