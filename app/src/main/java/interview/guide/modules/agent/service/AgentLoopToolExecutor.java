package interview.guide.modules.agent.service;

import interview.guide.modules.agent.metrics.AgentMetrics;
import java.time.Duration;
import java.util.Locale;

/**
 * Agent Loop 内置的只读工具执行器。
 *
 * <p>上下文由 {@link AgentContextService} 在 Loop 启动前按当前用户加载；这里不再
 * 重新查询或修改业务数据，只把规划器允许的三个观察动作明确执行为受预算约束的
 * 工具调用。工具参数和观察正文不会进入审计与指标。</p>
 */
public final class AgentLoopToolExecutor implements AgentLoopEngine.StepExecutor {

    static final String READ_INTERVIEW_REPORT = "read_interview_report";
    static final String CONFIRM_AGENT_GOAL = "confirm_agent_goal";
    static final String VERIFY_SKILL_RAG_SOURCES = "verify_skill_rag_sources";
    static final String ORGANIZE_AGENT_CONTEXT = "organize_agent_context";

    private final AgentMetrics metrics;
    private final AgentAuditService auditService;
    private final AgentMetrics.RunTracker runTracker;

    public AgentLoopToolExecutor(
        AgentMetrics metrics,
        AgentAuditService auditService,
        AgentMetrics.RunTracker runTracker
    ) {
        this.metrics = metrics;
        this.auditService = auditService;
        this.runTracker = runTracker;
    }

    @Override
    public String execute(AgentContextService.AgentContext context, String action, int index) {
        long startedNanos = System.nanoTime();
        String toolName = resolveToolName(action, index);
        try {
            if (runTracker != null) {
                runTracker.reserveToolCall();
            }
            String observation = observe(toolName, context);
            record(toolName, "success", startedNanos);
            return observation;
        } catch (RuntimeException error) {
            record(toolName, "failure", startedNanos);
            throw error;
        }
    }

    static String resolveToolName(String action, int index) {
        String normalized = action == null ? "" : action.toLowerCase(Locale.ROOT);
        if (normalized.contains("读取") && normalized.contains("报告")) {
            return READ_INTERVIEW_REPORT;
        }
        if (normalized.contains("核对")
            || normalized.contains("skill")
            || normalized.contains("rag")
            || normalized.contains("知识库")) {
            return VERIFY_SKILL_RAG_SOURCES;
        }
        if (normalized.contains("确认") && normalized.contains("目标")) {
            return CONFIRM_AGENT_GOAL;
        }
        return index == 0 ? READ_INTERVIEW_REPORT : ORGANIZE_AGENT_CONTEXT;
    }

    private String observe(String toolName, AgentContextService.AgentContext context) {
        AgentContextService.AgentContext safeContext = context == null
            ? new AgentContextService.AgentContext(
                "", "", "", "", "", java.util.List.of(), "", java.util.List.of()
            )
            : context;
        return switch (toolName) {
            case READ_INTERVIEW_REPORT -> safeContext.interviewReport() == null
                || safeContext.interviewReport().isBlank()
                ? "面试报告为空，后续回答不得推断未提供的表现。"
                : "已读取面试报告；识别到 " + safeContext.weaknesses().size() + " 个待关注项。";
            case CONFIRM_AGENT_GOAL -> "已确认当前目标和资料边界，围绕 "
                + safeText(safeContext.skillId(), "当前面试方向") + " 继续处理。";
            case VERIFY_SKILL_RAG_SOURCES -> {
                long skillSources = safeContext.sources().stream()
                    .filter(source -> source != null && "skill".equalsIgnoreCase(source.kind()))
                    .count();
                long ragSources = safeContext.sources().stream()
                    .filter(source -> source != null && "rag".equalsIgnoreCase(source.kind()))
                    .count();
                if (skillSources == 0 && ragSources == 0) {
                    yield "Skill/RAG 未提供可引用来源，本轮回答不得声称使用了外部依据。";
                }
                yield "已核对 Skill/RAG 来源：Skill " + skillSources + " 个，知识库 "
                    + ragSources + " 个。";
            }
            default -> "已整理固定资料边界，未声称使用不存在的外部依据。";
        };
    }

    private void record(String toolName, String result, long startedNanos) {
        Duration duration = Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
        if (metrics != null) {
            metrics.recordToolCall(toolName, result, duration);
        }
        if (auditService != null && runTracker != null) {
            AgentMetrics.AuditContext auditContext = runTracker.auditContext();
            auditService.recordQuietly(
                auditContext.ownerKey(), auditContext.interviewSessionId(), auditContext.agentSessionId(),
                auditContext.runId(), "TOOL_CALLED", "agent-loop-tool", toolName, result
            );
        }
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
