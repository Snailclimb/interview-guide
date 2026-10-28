package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentConversationSummary;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentRunUsage;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentConversationCreationRequestRepository;
import interview.guide.modules.agent.model.AgentConversationCreationRequestEntity;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
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

    @Mock
    private AgentAuditService auditService;

    @Mock
    private AgentConversationCreationRequestRepository creationRequestRepository;

    @InjectMocks
    private AgentConversationService service;

    @Test
    @DisplayName("准备 Agent 消息时创建独立会话、用户消息和助手占位消息")
    void prepareMessageCreatesSessionAndMessagePair() {
        when(sessionRepository.findByInterviewSessionIdForUpdate("interview-1"))
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
    @DisplayName("未指定线程时只锁最早默认线程，不因同一报告有多个线程而报非唯一结果")
    void prepareMessageUsesOldestThreadWhenMultipleThreadsExist() {
        AgentChatSessionEntity oldest = AgentChatSessionEntity.builder()
            .id(12L)
            .interviewSessionId("interview-1")
            .agentSessionId("oldest-thread")
            .title("最早线程")
            .runStatus(AgentRunStatus.IDLE)
            .build();
        when(sessionRepository.findTop1ByInterviewSessionIdOrderByCreatedAtAsc("interview-1"))
            .thenReturn(List.of(oldest));
        when(messageRepository.findTopBySessionIdOrderBySequenceNumDesc(12L))
            .thenReturn(Optional.empty());
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any(AgentChatMessageEntity.class)))
            .thenAnswer(invocation -> {
                AgentChatMessageEntity message = invocation.getArgument(0);
                if (message.getRole() == AgentChatMessageEntity.MessageRole.ASSISTANT) {
                    message.setId(23L);
                }
                return message;
            });

        AgentConversationService.PreparedMessage prepared = service.prepareMessage(
            "interview-1", "review", null, "继续复盘"
        );

        assertThat(prepared.agentSessionId()).isEqualTo("oldest-thread");
        verify(sessionRepository, never()).findByInterviewSessionIdForUpdate("interview-1");
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
            .runPromptTokens(120)
            .runCompletionTokens(80)
            .runTotalTokens(200)
            .runModelCalls(2)
            .runDurationMillis(1250L)
            .runCost(new BigDecimal("0.00048"))
            .runCurrency("USD")
            .runProvider("pixel")
            .runModel("gpt-5.6-luna")
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
        assertThat(response.runUsage().totalTokens()).isEqualTo(200);
        assertThat(response.runUsage().modelCalls()).isEqualTo(2);
        assertThat(response.runUsage().cost()).isEqualByComparingTo("0.00048");
        assertThat(response.runUsage().model()).isEqualTo("gpt-5.6-luna");
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
    @DisplayName("应用重启后旧运行的迟到终态事件不会取消新接管任务")
    void ignoresLateTerminalEventFromPreviousRunId() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runId("recovered-run")
            .runStatus(AgentRunStatus.RUNNING)
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

        service.cancelMessage(22L, "旧进程的迟到取消", "previous-run");

        assertThat(session.getRunStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(message.getCompleted()).isFalse();
        verify(messageRepository, org.mockito.Mockito.never()).save(message);
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
    @DisplayName("恢复后旧运行代次的进度、来源和用量不能覆盖新任务")
    void ignoresProgressFromPreviousRunId() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(11L)
            .interviewSessionId("interview-1")
            .runId("run-new")
            .runStatus(AgentRunStatus.RUNNING)
            .activeMessageId(22L)
            .runStep("loop")
            .runStepStatus("running")
            .runNotice("新任务正在执行")
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

        service.updateRunProgress(22L, "context", "running", "旧任务迟到事件", "run-old");
        service.updateRunUsage(22L, AgentRunUsage.empty(), "run-old");

        assertThat(session.getRunStep()).isEqualTo("loop");
        assertThat(session.getRunNotice()).isEqualTo("新任务正在执行");
        verify(sessionRepository, never()).save(session);
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

    @Test
    @DisplayName("同一份面试报告可以创建两个互不相同的 Agent 对话线程")
    void createsIndependentConversationThreads() {
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentConversationSummary first = service.createConversation("interview-1", "复盘线程");
        AgentConversationSummary second = service.createConversation("interview-1", "训练线程");

        assertThat(first.agentSessionId()).isNotBlank().isNotEqualTo(second.agentSessionId());
        assertThat(first.interviewSessionId()).isEqualTo("interview-1");
        assertThat(first.title()).isEqualTo("复盘线程");
        assertThat(second.title()).isEqualTo("训练线程");
    }

    @Test
    @DisplayName("同一创建请求重试时复用已有 Agent 对话，不重复创建线程")
    void reusesConversationForRepeatedCreationRequest() {
        AgentConversationCreationRequestEntity request = AgentConversationCreationRequestEntity.builder()
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .idempotencyKey("create-1")
            .agentSessionId("agent-existing")
            .build();
        AgentChatSessionEntity existing = AgentChatSessionEntity.builder()
            .id(12L)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-existing")
            .ownerKey("user-1")
            .title("第一次创建")
            .runStatus(AgentRunStatus.IDLE)
            .build();
        when(creationRequestRepository.tryClaim(
            eq("user-1"), eq("interview-1"), eq("create-1"), any()
        ))
            .thenReturn(0);
        when(creationRequestRepository.findByOwnerKeyAndIdempotencyKey("user-1", "create-1"))
            .thenReturn(Optional.of(request));
        when(sessionRepository.findByAgentSessionId("agent-existing"))
            .thenReturn(Optional.of(existing));

        AgentConversationSummary result = service.createConversation(
            "interview-1", "重试标题", "user-1", "create-1"
        );

        assertThat(result.agentSessionId()).isEqualTo("agent-existing");
        assertThat(result.title()).isEqualTo("第一次创建");
        verify(sessionRepository, never()).save(any(AgentChatSessionEntity.class));
        verify(auditService, never()).recordQuietly(
            any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("会话生命周期写入低敏审计，但不把标题或消息正文作为审计资源")
    void auditsConversationLifecycleWithOnlyStableIdentifiers() {
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentConversationSummary created = service.createConversation(
            "interview-1", "Redis 复盘线程", "browser-a"
        );
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("interview-1")
            .agentSessionId(created.agentSessionId())
            .ownerKey("browser-a")
            .memoryKey("browser-a")
            .title("Redis 复盘线程")
            .runStatus(AgentRunStatus.IDLE)
            .build();
        when(sessionRepository.findByAgentSessionId(created.agentSessionId()))
            .thenReturn(Optional.of(session));
        when(sessionRepository.findByAgentSessionIdForUpdate(created.agentSessionId()))
            .thenReturn(Optional.of(session));

        service.renameConversation(
            "interview-1", created.agentSessionId(), "新的复盘线程", "browser-a"
        );
        service.archiveConversation("interview-1", created.agentSessionId(), "browser-a");

        var actions = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService, org.mockito.Mockito.times(3)).recordQuietly(
            org.mockito.ArgumentMatchers.eq("browser-a"),
            org.mockito.ArgumentMatchers.eq("interview-1"),
            org.mockito.ArgumentMatchers.eq(created.agentSessionId()),
            org.mockito.ArgumentMatchers.isNull(),
            actions.capture(),
            org.mockito.ArgumentMatchers.eq("agent-session"),
            org.mockito.ArgumentMatchers.eq(created.agentSessionId()),
            org.mockito.ArgumentMatchers.eq("success")
        );

        assertThat(actions.getAllValues()).containsExactly(
            "CONVERSATION_CREATED", "CONVERSATION_RENAMED", "CONVERSATION_ARCHIVED"
        );
    }

    @Test
    @DisplayName("不同身份不能读取或修改 Agent 对话线程")
    void isolatesConversationByOwnerKey() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(20L)
            .interviewSessionId("interview-1")
            .agentSessionId("thread-owner")
            .ownerKey("browser-a")
            .memoryKey("browser-a")
            .title("我的线程")
            .runStatus(AgentRunStatus.IDLE)
            .build();
        when(sessionRepository.findByAgentSessionId("thread-owner")).thenReturn(Optional.of(session));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.assertAccess("interview-1", "thread-owner", "browser-b")
        ).isInstanceOf(interview.guide.common.exception.BusinessException.class)
            .hasMessage("无权访问该 Agent 会话");

        when(sessionRepository.findByInterviewSessionIdOrderByUpdatedAtDesc("interview-1"))
            .thenReturn(List.of(session));
        assertThat(service.listConversations("interview-1", "browser-b")).isEmpty();
    }

    @Test
    @DisplayName("会话名称修改会保留线程 ID 和归属身份")
    void renamesOwnedConversation() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(21L)
            .interviewSessionId("interview-1")
            .agentSessionId("thread-owner")
            .ownerKey("browser-a")
            .title("旧名称")
            .runStatus(AgentRunStatus.IDLE)
            .build();
        when(sessionRepository.findByAgentSessionId("thread-owner")).thenReturn(Optional.of(session));
        when(sessionRepository.findByAgentSessionIdForUpdate("thread-owner"))
            .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentConversationSummary renamed = service.renameConversation(
            "interview-1", "thread-owner", "新的复盘线程", "browser-a"
        );

        assertThat(renamed.agentSessionId()).isEqualTo("thread-owner");
        assertThat(renamed.title()).isEqualTo("新的复盘线程");
        assertThat(session.getOwnerKey()).isEqualTo("browser-a");
    }

    @Test
    @DisplayName("归档会话只隐藏列表，不删除已有消息")
    void archivesOwnedConversationWithoutDeletingData() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(22L)
            .interviewSessionId("interview-1")
            .agentSessionId("thread-archive")
            .ownerKey("browser-a")
            .title("待归档")
            .runStatus(AgentRunStatus.COMPLETED)
            .build();
        when(sessionRepository.findByAgentSessionId("thread-archive")).thenReturn(Optional.of(session));
        when(sessionRepository.findByAgentSessionIdForUpdate("thread-archive"))
            .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AgentChatSessionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.archiveConversation("interview-1", "thread-archive", "browser-a");

        assertThat(session.isArchived()).isTrue();
    }

    @Test
    @DisplayName("按 Agent 线程 ID 读取时不会回退到另一份面试对话")
    void readsConversationByExplicitThreadId() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(12L)
            .interviewSessionId("interview-1")
            .agentSessionId("thread-b")
            .title("训练线程")
            .runStatus(AgentRunStatus.COMPLETED)
            .build();
        AgentChatMessageEntity message = AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("只属于训练线程的回答")
            .sequenceNum(1)
            .completed(true)
            .build();
        when(sessionRepository.findByAgentSessionId("thread-b")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
            org.mockito.ArgumentMatchers.eq(12L), any(Pageable.class)
        )).thenReturn(List.of(message));

        AgentConversationResponse response = service.getConversation("interview-1", "thread-b");

        assertThat(response.agentSessionId()).isEqualTo("thread-b");
        assertThat(response.title()).isEqualTo("训练线程");
        assertThat(response.messages()).extracting(AgentHistoryMessage::content)
            .containsExactly("只属于训练线程的回答");
        verify(sessionRepository).findByAgentSessionId("thread-b");
    }
}
