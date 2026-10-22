package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@DisplayName("Agent 运行进度异步写入")
class AgentProgressWriterTest {

    @Test
    @DisplayName("短时间内重复进度只持久化最新步骤并合并来源")
    void coalescesLatestProgress() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofMillis(20)
        );

        writer.recordStep(10L, "context", "running", null);
        writer.recordStep(10L, "insight", "running", null);
        writer.recordSources(10L, List.of(new AgentSource("rag", "R1", 0.9, "R1")));

        verify(conversationService, timeout(1000))
            .updateRunProgress(10L, "insight", "running", null);
        verify(conversationService, timeout(1000))
            .updateRunSources(10L, List.of(new AgentSource("rag", "R1", 0.9, "R1")));
        verify(conversationService, never())
            .updateRunProgress(10L, "context", "running", null);
    }

    @Test
    @DisplayName("任务终态后丢弃尚未写入的旧进度")
    void dropsPendingProgressAfterFinish() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofSeconds(1)
        );

        writer.recordStep(20L, "action", "running", null);
        writer.finish(20L);

        verify(conversationService, after(200).never())
            .updateRunProgress(20L, "action", "running", null);
    }

    @Test
    @DisplayName("任务结束后迟到的进度事件不会重新创建写入任务")
    void ignoresLateProgressAfterFinish() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofMillis(10)
        );

        writer.finish(30L);
        writer.recordStep(30L, "action", "running", "迟到事件");
        writer.recordSources(30L, List.of(new AgentSource("rag", "R1", 0.9, "R1")));

        verify(conversationService, after(200).never())
            .updateRunProgress(30L, "action", "running", "迟到事件");
        verify(conversationService, after(200).never())
            .updateRunSources(30L, List.of(new AgentSource("rag", "R1", 0.9, "R1")));
    }
}
