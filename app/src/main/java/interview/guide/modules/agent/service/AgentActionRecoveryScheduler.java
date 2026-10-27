package interview.guide.modules.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期回收应用重启或进程崩溃后遗留的 Agent 动作。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentActionRecoveryScheduler {
    private final AgentActionService actionService;

    @Scheduled(
        fixedDelayString = "${agent.action-recovery.fixed-delay-ms:60000}",
        initialDelayString = "${agent.action-recovery.initial-delay-ms:15000}"
    )
    public void recoverStaleActions() {
        try {
            int recovered = actionService.recoverStaleRunningActions();
            if (recovered > 0) {
                log.info("Agent 动作恢复完成: recovered={}", recovered);
            }
        } catch (RuntimeException error) {
            log.warn("Agent 动作恢复失败，将在下一轮重试", error);
        }
    }
}
