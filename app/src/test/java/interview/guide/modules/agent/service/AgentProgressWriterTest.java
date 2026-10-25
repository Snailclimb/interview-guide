package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("任务快速结束时终态刷出延迟窗口内的最新进度和来源")
    void flushesPendingProgressBeforeTerminalPersistence() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofSeconds(5)
        );
        List<AgentSource> sources = List.of(new AgentSource("rag", "R1", 0.9, "R1"));

        writer.recordStep(40L, "action", "running", "正在生成");
        writer.recordSources(40L, sources);
        writer.flush(40L);

        verify(conversationService).updateRunProgress(40L, "action", "running", "正在生成");
        verify(conversationService).updateRunSources(40L, sources);
    }

    @Test
    @DisplayName("终态刷盘前持久化 Loop 计划和下一个步骤")
    void flushesLoopResumeState() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofSeconds(5)
        );

        writer.recordLoopState(45L, "1. 读取报告\n2. 整理建议", 1);
        writer.flush(45L);

        verify(conversationService).updateLoopState(45L, "1. 读取报告\n2. 整理建议", 1);
    }

    @Test
    @DisplayName("带运行代次写入进度，避免恢复前旧任务覆盖新任务")
    void carriesRunIdToPersistenceBoundary() {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ofSeconds(5)
        );

        writer.recordLoopState(46L, "run-new", "1. 读取报告", 1);
        writer.flush(46L);

        verify(conversationService).updateLoopState(46L, "1. 读取报告", 1, "run-new");
    }

    @Test
    @DisplayName("终态刷盘与延迟写入并发时保持写入顺序")
    void serializesTerminalFlushAndDelayedDrain() throws Exception {
        AgentConversationService conversationService = mock(AgentConversationService.class);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            firstWriteStarted.countDown();
            releaseFirstWrite.await(1, TimeUnit.SECONDS);
            return null;
        }).when(conversationService).updateRunProgress(50L, "context", "running", null);
        AgentProgressWriter writer = new AgentProgressWriter(
            conversationService, Duration.ZERO
        );
        writer.recordStep(50L, "context", "running", null);
        assertThat(firstWriteStarted.await(1, TimeUnit.SECONDS)).isTrue();

        writer.recordStep(50L, "action", "running", null);
        Thread finishThread = new Thread(() -> writer.finish(50L));
        finishThread.start();
        releaseFirstWrite.countDown();
        finishThread.join(1000);

        verify(conversationService).updateRunProgress(50L, "context", "running", null);
        verify(conversationService, never()).updateRunProgress(50L, "action", "running", null);
    }
}
