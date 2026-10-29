package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.model.AgentRunUsage;
import interview.guide.modules.agent.model.AgentRunUsageEntity;
import interview.guide.modules.agent.model.AgentUsageCostSummary;
import interview.guide.modules.agent.model.AgentUsageSummaryResponse;
import interview.guide.modules.agent.model.AgentUsageRunResponse;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.agent.repository.AgentRunUsageRepository;
import interview.guide.modules.agent.repository.AgentRunUsageRepository.AgentUsageCostProjection;
import interview.guide.modules.agent.repository.AgentRunUsageRepository.AgentUsageTotalsProjection;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 记录一次运行并汇总当前用户的 Agent 用量。 */
@Service
@RequiredArgsConstructor
public class AgentUsageService {

    private static final Set<AgentRunStatus> TERMINAL_STATUSES = EnumSet.of(
        AgentRunStatus.COMPLETED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED
    );

    private final AgentRunUsageRepository usageRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final AgentChatSessionRepository agentChatSessionRepository;

    /**
     * 仅在运行进入终态后记录；模型重试过程中的中间快照不会制造重复运行记录。
     * runId 唯一也让重复的终态回调变成幂等更新。
     */
    @Transactional
    public void record(AgentChatSessionEntity session, AgentRunUsage usage) {
        if (session == null
            || session.getRunId() == null || session.getRunId().isBlank()
            || !TERMINAL_STATUSES.contains(session.getRunStatus())) {
            return;
        }

        AgentRunUsage safe = usage == null ? AgentRunUsage.empty() : usage;
        AgentRunUsageEntity entity = usageRepository.findByRunId(session.getRunId())
            .orElseGet(AgentRunUsageEntity::new);
        entity.setOwnerKey(normalize(session.getOwnerKey()));
        entity.setInterviewSessionId(session.getInterviewSessionId());
        entity.setAgentSessionId(session.getAgentSessionId());
        entity.setRunId(session.getRunId());
        entity.setRunStatus(session.getRunStatus());
        entity.setRunStartedAt(session.getRunStartedAt());
        entity.setRunFinishedAt(session.getRunFinishedAt());
        entity.setPromptTokens(safe.promptTokens());
        entity.setCompletionTokens(safe.completionTokens());
        entity.setTotalTokens(safe.totalTokens());
        entity.setModelCalls(safe.modelCalls());
        entity.setDurationMillis(safe.durationMillis());
        entity.setCost(safe.cost());
        entity.setCurrency(normalize(safe.currency()));
        entity.setProvider(normalize(safe.provider()));
        entity.setModel(normalize(safe.model()));
        usageRepository.save(entity);
    }

    @Transactional
    public AgentUsageSummaryResponse summarize(String ownerKey) {
        String normalizedOwnerKey = normalize(ownerKey);
        if (normalizedOwnerKey == null) {
            return AgentUsageSummaryResponse.empty();
        }

        AgentUsageTotalsProjection totals = usageRepository.summarizeTotals(normalizedOwnerKey);
        long runCount = usageRepository.countByOwnerKey(normalizedOwnerKey);
        long completedRunCount = usageRepository.countByOwnerKeyAndRunStatus(
            normalizedOwnerKey, AgentRunStatus.COMPLETED
        );
        long failedRunCount = usageRepository.countByOwnerKeyAndRunStatus(
            normalizedOwnerKey, AgentRunStatus.FAILED
        );
        long cancelledRunCount = usageRepository.countByOwnerKeyAndRunStatus(
            normalizedOwnerKey, AgentRunStatus.CANCELLED
        );

        List<AgentUsageCostSummary> costSummaries = new ArrayList<>(
            usageRepository.summarizeCosts(normalizedOwnerKey).stream()
                .map(this::toCostSummary)
                .toList()
        );
        costSummaries.sort((left, right) -> {
            int provider = Objects.toString(left.provider(), "")
                .compareToIgnoreCase(Objects.toString(right.provider(), ""));
            return provider != 0
                ? provider
                : Objects.toString(left.currency(), "")
                    .compareToIgnoreCase(Objects.toString(right.currency(), ""));
        });

        return new AgentUsageSummaryResponse(
            runCount, completedRunCount, failedRunCount, cancelledRunCount,
            value(totals == null ? null : totals.getPromptTokens()),
            value(totals == null ? null : totals.getCompletionTokens()),
            value(totals == null ? null : totals.getTotalTokens()),
            value(totals == null ? null : totals.getModelCalls()),
            value(totals == null ? null : totals.getDurationMillis()),
            costSummaries
        );
    }

    /** 查询当前用户最近的单次运行明细，用于定位具体面试和 Agent 会话的消耗。 */
    @Transactional
    public List<AgentUsageRunResponse> listRuns(String ownerKey, int limit) {
        String normalizedOwnerKey = normalize(ownerKey);
        if (normalizedOwnerKey == null) return List.of();
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<AgentRunUsageEntity> runs = usageRepository.findTop100ByOwnerKeyOrderByCreatedAtDesc(normalizedOwnerKey)
            .stream().limit(safeLimit).toList();
        if (runs.isEmpty()) return List.of();

        Map<String, InterviewSessionEntity> interviews = interviewSessionRepository == null
            ? Map.of()
            : interviewSessionRepository.findAllBySessionIdInAndOwnerId(runs.stream()
                .map(AgentRunUsageEntity::getInterviewSessionId).filter(Objects::nonNull).collect(Collectors.toSet()), normalizedOwnerKey)
                .stream().collect(Collectors.toMap(InterviewSessionEntity::getSessionId, Function.identity(), (left, right) -> left));
        Map<String, AgentChatSessionEntity> agentSessions = agentChatSessionRepository == null
            ? Map.of()
            : agentChatSessionRepository.findAllByAgentSessionIdInAndOwnerKey(runs.stream()
                .map(AgentRunUsageEntity::getAgentSessionId).filter(Objects::nonNull).collect(Collectors.toSet()), normalizedOwnerKey)
                .stream().collect(Collectors.toMap(AgentChatSessionEntity::getAgentSessionId, Function.identity(), (left, right) -> left));

        return runs.stream().map(run -> {
            InterviewSessionEntity interview = interviews.get(run.getInterviewSessionId());
            AgentChatSessionEntity agentSession = agentSessions.get(run.getAgentSessionId());
            return new AgentUsageRunResponse(
                run.getRunId(),
                run.getInterviewSessionId(),
                interviewTitle(interview),
                interview == null ? null : interview.getSkillId(),
                run.getAgentSessionId(),
                agentSession == null ? null : agentSession.getTitle(),
                run.getRunStatus() == null ? null : run.getRunStatus().name(),
                run.getPromptTokens(), run.getCompletionTokens(), run.getTotalTokens(),
                run.getModelCalls(), run.getDurationMillis(), run.getCost(), run.getCurrency(),
                run.getProvider(), run.getModel(), run.getRunStartedAt(), run.getRunFinishedAt(), run.getCreatedAt()
            );
        }).toList();
    }

    private String interviewTitle(InterviewSessionEntity interview) {
        if (interview == null) return null;
        if (interview.getTitle() != null && !interview.getTitle().isBlank()) return interview.getTitle();
        return interview.getSkillId();
    }

    private AgentUsageCostSummary toCostSummary(AgentUsageCostProjection projection) {
        return new AgentUsageCostSummary(
            normalize(projection.getProvider()),
            normalize(projection.getCurrency()),
            projection.getAmount() == null || projection.getAmount().signum() < 0
                ? BigDecimal.ZERO : projection.getAmount()
        );
    }

    private long value(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

}
