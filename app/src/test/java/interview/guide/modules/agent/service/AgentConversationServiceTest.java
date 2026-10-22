package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationServiceTest {

    @Mock
    private AgentChatSessionRepository sessionRepository;

    @Mock
    private AgentChatMessageRepository messageRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AgentConversationService service;

    @Test
    @DisplayName("准备 Agent 消息时创建独立会话、用户消息和助手占位消息")
    void prepareMessageCreatesSessionAndMessagePair() {
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
            .thenReturn(Optional.empty());
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.empty());
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> {
                AgentChatSessionEntity session = invocation.getArgument(0);
                session.setId(11L);
                return session;
            });
        when(messageRepository.findTopBySessionIdOrderBySequenceNumDesc(11L))
            .thenReturn(Optional.empty());
        when(messageRepository.save(any(AgentChatMessageEntity.class)))
            .thenAnswer(invocation -> {
                AgentChatMessageEntity message = invocation.getArgument(0);
                if (message.getRole() == AgentChatMessageEntity.MessageRole.ASSISTANT) {
                    message.setId(22L);
                }
                return message;
            });

        AgentConversationService.PreparedMessage prepared = service.prepareMessage(
            "interview-1", "review", null, "请复盘一下"
        );

        assertThat(prepared.assistantMessageId()).isEqualTo(22L);
        assertThat(prepared.runId()).isNotBlank();
        ArgumentCaptor<AgentChatMessageEntity> captor =
            ArgumentCaptor.forClass(AgentChatMessageEntity.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(AgentChatMessageEntity::getRole)
            .containsExactly(
                AgentChatMessageEntity.MessageRole.USER,
                AgentChatMessageEntity.MessageRole.ASSISTANT
            );
        assertThat(captor.getAllValues().get(0).getContent()).isEqualTo("请复盘一下");
        assertThat(captor.getAllValues().get(1).getCompleted()).isFalse();
    }

    @Test
    @DisplayName("恢复会话时只返回已完成且有内容的消息")
    void getConversationFiltersIncompleteMessages() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .goal("review")
            .providerId("pixel")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.COMPLETED)
            .build();
        AgentChatMessageEntity user = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content("请复盘")
            .sequenceNum(0)
            .completed(true)
            .build();
        AgentChatMessageEntity assistant = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("复盘结果")
            .sequenceNum(1)
            .completed(true)
            .build();
        AgentChatMessageEntity incomplete = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("半截回答")
            .sequenceNum(2)
            .completed(false)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(11L), any(Pageable.class)
        ))
            .thenReturn(List.of(assistant, user));

        AgentConversationResponse response = service.getConversation("interview-1");

        assertThat(response.goal()).isEqualTo("review");
        assertThat(response.providerId()).isEqualTo("pixel");
        assertThat(response.messages()).extracting("role")
            .containsExactly("user", "assistant");
        assertThat(response.messages()).extracting("content")
            .containsExactly("请复盘", "复盘结果");
    }

    @Test
    @DisplayName("失败任务恢复时返回半截回答，但不把它当作已完成历史")
    void exposesPartialAnswerForFailedRunWithoutAddingItToHistory() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.FAILED)
            .runError("模型响应超时")
            .build();
        AgentChatMessageEntity user = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content("请复盘")
            .sequenceNum(2)
            .completed(true)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(11L), any(Pageable.class)
        )).thenReturn(List.of(user));
        AgentChatMessageEntity partial = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("## 已完成的分析\n\n这里是已经生成的部分")
            .sequenceNum(3)
            .completed(false)
            .build();
        when(messageRepository.findTopBySessionIdAndCompletedFalseOrderBySequenceNumDesc(11L))
            .thenReturn(Optional.of(partial));

        AgentConversationResponse response = service.getConversation("interview-1");

        assertThat(response.runPartialAnswer()).isEqualTo(partial.getContent());
        assertThat(response.messages()).extracting(AgentHistoryMessage::content)
            .containsExactly("请复盘");
    }

    @Test
    @DisplayName("旧任务迟到的完成事件不会覆盖当前运行任务")
    void ignoresLateCompletionFromStaleMessage() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.RUNNING)
            .activeMessageId(99L)
            .build();
        AgentChatMessageEntity staleMessage = AgentChatMessageEntity.builder()
            .id(88L)
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("")
            .sequenceNum(1)
            .completed(false)
            .build();
        when(messageRepository.findById(88L)).thenReturn(Optional.of(staleMessage));

        service.completeMessage(88L, "旧任务输出");

        assertThat(staleMessage.getCompleted()).isFalse();
        assertThat(session.getRunStatus())
            .isEqualTo(interview.guide.modules.agent.model.AgentRunStatus.RUNNING);
        verify(messageRepository, org.mockito.Mockito.never()).save(staleMessage);
    }

    @Test
    @DisplayName("读取会话时自动回收超过 15 分钟的运行任务")
    void recoversStaleRunWhenReadingConversation() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.RUNNING)
            .runStartedAt(LocalDateTime.now().minusMinutes(16))
            .activeMessageId(22L)
            .build();
        AgentChatMessageEntity activeMessage = AgentChatMessageEntity.builder()
            .id(22L)
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("半截回答")
            .sequenceNum(1)
            .completed(false)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findById(22L)).thenReturn(Optional.of(activeMessage));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(11L), any(Pageable.class)
        )).thenReturn(List.of());

        AgentConversationResponse response = service.getConversation("interview-1");

        assertThat(response.runStatus())
            .isEqualTo(interview.guide.modules.agent.model.AgentRunStatus.FAILED);
        assertThat(response.runError()).contains("超过最大执行时间");
        assertThat(session.getActiveMessageId()).isNull();
        assertThat(activeMessage.getErrorMessage()).contains("超过最大执行时间");
    }

    @Test
    @DisplayName("只有同一个运行标识且状态仍为运行中时任务才有效")
    void checksActiveRunIdentity() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("interview-1")
            .runId("run-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.RUNNING)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));

        assertThat(service.isRunActive("interview-1", "run-1")).isTrue();
        assertThat(service.isRunActive("interview-1", "run-2")).isFalse();
        session.setRunStatus(interview.guide.modules.agent.model.AgentRunStatus.CANCELLED);
        assertThat(service.isRunActive("interview-1", "run-1")).isFalse();
    }

    @Test
    @DisplayName("失败任务的悬空用户消息不会重复进入下一轮模型上下文")
    void excludesDanglingUserMessageAfterFailedRun() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.FAILED)
            .runError("模型不可用")
            .build();
        AgentChatMessageEntity user = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content("失败的问题")
            .sequenceNum(2)
            .completed(true)
            .build();
        AgentChatMessageEntity previousAssistant = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("之前的回答")
            .sequenceNum(1)
            .completed(true)
            .build();
        when(sessionRepository.findByInterviewSessionId("interview-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(11L), any(Pageable.class)
        )).thenReturn(List.of(user, previousAssistant));

        List<AgentHistoryMessage> history = service.loadHistory("interview-1");

        assertThat(history).extracting(AgentHistoryMessage::content)
            .containsExactly("之前的回答");
    }

    @Test
    @DisplayName("运行进度和来源只更新当前有效任务")
    void persistsActiveRunProgressAndSourceSummary() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.RUNNING)
            .activeMessageId(22L)
            .build();
        AgentChatMessageEntity activeMessage = AgentChatMessageEntity.builder()
            .id(22L)
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("")
            .sequenceNum(1)
            .completed(false)
            .build();
        when(messageRepository.findById(22L)).thenReturn(Optional.of(activeMessage));
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
            .thenReturn(Optional.of(session));
        when(objectMapper.writeValueAsString(any())).thenReturn(
            "[{\"kind\":\"rag\",\"title\":\"知识库片段\",\"score\":0.9,\"reference\":\"R1\"}]"
        );

        service.updateRunProgress(22L, "retrieval", "waiting", "正在等待检索资源");
        service.updateRunSources(22L, List.of(
            new AgentSource("rag", "知识库片段", 0.9, "R1")
        ));

        assertThat(session.getRunStep()).isEqualTo("retrieval");
        assertThat(session.getRunStepStatus()).isEqualTo("waiting");
        assertThat(session.getRunNotice()).isEqualTo("正在等待检索资源");
        assertThat(session.getRunSourcesJson()).contains("知识库片段").doesNotContain("原始 RAG");
        verify(sessionRepository, times(2)).save(session);
    }

    @Test
    @DisplayName("终态只允许写入一次，迟到的取消和进度事件不会覆盖完成结果")
    void ignoresLateTerminalAndProgressEventsAfterCompletion() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runStatus(interview.guide.modules.agent.model.AgentRunStatus.RUNNING)
            .activeMessageId(22L)
            .build();
        AgentChatMessageEntity message = AgentChatMessageEntity.builder()
            .id(22L)
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("")
            .sequenceNum(1)
            .completed(false)
            .build();
        when(messageRepository.findById(22L)).thenReturn(Optional.of(message));
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
            .thenReturn(Optional.of(session));

        service.completeMessage(22L, "最终回答");
        service.cancelMessage(22L, "迟到的取消");
        service.updateRunProgress(22L, "action", "running", "迟到的进度");

        assertThat(session.getRunStatus())
            .isEqualTo(interview.guide.modules.agent.model.AgentRunStatus.COMPLETED);
        assertThat(session.getActiveMessageId()).isNull();
        assertThat(message.getContent()).isEqualTo("最终回答");
        assertThat(message.getCompleted()).isTrue();
        verify(messageRepository).save(message);
        verify(sessionRepository).save(session);
    }
}
