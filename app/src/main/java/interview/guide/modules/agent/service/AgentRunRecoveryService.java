package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 应用重启后的 Agent 任务接管器。
 *
 * <p>只接管持久化为 RUNNING 且超过安全年龄的任务；接管动作由会话级悲观锁保护，
 * 因此多实例部署时同一任务只会被一个实例重新执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunRecoveryService {
    private final AgentChatSessionRepository sessionRepository;
    private final AgentConversationService conversationService;
    private final InterviewAgentService agentService;
    private final AgentRetryProperties retryProperties;

    @Scheduled(fixedDelayString = "${app.ai.agent.recovery-scan-interval-ms:10000}")
    public void recoverInterruptedRuns() {
        if (!retryProperties.isRecoveryEnabled()) {
            return;
        }
        LocalDateTime staleBefore = LocalDateTime.now()
            .minusSeconds(retryProperties.getRecoveryMinAgeSeconds());
        sessionRepository.findByRunStatusAndRunStartedAtBefore(AgentRunStatus.RUNNING, staleBefore)
            .stream()
            .limit(16)
            .forEach(session -> {
                if (agentService.isLocallyActive(session.getInterviewSessionId())) {
                    return;
                }
                try {
                    AgentConversationService.RecoveredRun recovered = conversationService.claimRecoverableRun(
                        session.getInterviewSessionId(), staleBefore
                    );
                    if (recovered != null) {
                        log.info("接管应用重启遗留 Agent 任务: sessionId={}, oldRunId={}",
                            recovered.interviewSessionId(), session.getRunId());
                        agentService.recoverRun(recovered);
                    }
                } catch (Throwable error) {
                    log.warn("Agent 启动恢复扫描失败: sessionId={}", session.getInterviewSessionId(), error);
                }
            });
    }
}
