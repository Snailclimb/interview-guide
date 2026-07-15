package interview.guide.modules.agentinterview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import interview.guide.common.agent.config.AgentProperties;
import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.common.agent.runtime.AgentType;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.infrastructure.agent.persistence.AnswerMessageEntity;
import interview.guide.infrastructure.agent.persistence.AnswerMessageRepository;
import interview.guide.infrastructure.agent.persistence.AgentCheckpointEntity;
import interview.guide.infrastructure.agent.persistence.AgentCheckpointRepository;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import interview.guide.infrastructure.agent.persistence.AgentStepEntity;
import interview.guide.infrastructure.agent.persistence.AgentStepRepository;
import interview.guide.modules.agentinterview.controller.AgentRunController;
import interview.guide.modules.agentinterview.service.AgentCheckpointService;
import interview.guide.modules.agentinterview.service.AgentRunService;
import interview.guide.modules.agentinterview.service.AgentRunPersistenceService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Run API 契约测试")
class AgentRunApiContractTest {

  private static final String BUSINESS_SESSION_ID = "interview-session-001";
  private static final String IDEMPOTENCY_KEY = "start-agent-interview-001";

  @Mock
  private AgentRunRepository agentRunRepository;

  @Mock
  private AnswerMessageRepository answerMessageRepository;

  @Mock
  private AgentStepRepository agentStepRepository;

  @Mock
  private AgentCheckpointRepository agentCheckpointRepository;

  @Mock
  private InterviewSessionRepository interviewSessionRepository;

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private AtomicReference<AgentRunEntity> savedRun;
  private List<AnswerMessageEntity> savedAnswerMessages;
  private List<AgentStepEntity> savedSteps;
  private List<AgentCheckpointEntity> savedCheckpoints;
  private AgentProperties agentProperties;

  @BeforeEach
  void setUp() {
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    savedRun = new AtomicReference<>();
    savedAnswerMessages = new ArrayList<>();
    savedSteps = new ArrayList<>();
    savedCheckpoints = new ArrayList<>();
    AgentRunPersistenceService persistenceService = new AgentRunPersistenceService(agentRunRepository);
    AgentCheckpointService checkpointService = new AgentCheckpointService(
        agentCheckpointRepository,
        objectMapper
    );
    agentProperties = new AgentProperties();
    agentProperties.setEnabled(true);
    AgentRunService service = new AgentRunService(
        agentRunRepository,
        answerMessageRepository,
        agentStepRepository,
        interviewSessionRepository,
        persistenceService,
        checkpointService,
        agentProperties
    );
    AgentRunController controller = new AgentRunController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    lenient().when(interviewSessionRepository.findBySessionId(BUSINESS_SESSION_ID))
        .thenReturn(Optional.of(new InterviewSessionEntity()));
    lenient().when(agentRunRepository.saveAndFlush(any())).thenAnswer(invocation -> {
      var entity = invocation.getArgument(0, AgentRunEntity.class);
      savedRun.set(entity);
      return entity;
    });
    lenient().when(agentRunRepository.findById(any())).thenAnswer(invocation -> {
      var entity = savedRun.get();
      String runId = invocation.getArgument(0, String.class);
      return entity != null && entity.getRunId().equals(runId)
          ? Optional.of(entity)
          : Optional.empty();
    });
    lenient().when(agentRunRepository.findByIdempotencyKey(any())).thenAnswer(invocation -> {
      var entity = savedRun.get();
      String idempotencyKey = invocation.getArgument(0, String.class);
      return entity != null && entity.getIdempotencyKey().equals(idempotencyKey)
          ? Optional.of(entity)
          : Optional.empty();
    });
    lenient().when(agentRunRepository.advanceWaitingUserToRunning(
            any(), any(), any()))
        .thenAnswer(invocation -> {
          AgentRunEntity entity = savedRun.get();
          String runId = invocation.getArgument(0, String.class);
          String questionId = invocation.getArgument(1, String.class);
          if (entity == null
              || !entity.getRunId().equals(runId)
              || entity.getStatus() != AgentRunStatus.WAITING_USER
              || !questionId.equals(entity.getCurrentQuestionId())) {
            return 0;
          }
          ReflectionTestUtils.setField(entity, "status", AgentRunStatus.RUNNING);
          ReflectionTestUtils.setField(entity, "currentQuestionId", null);
          return 1;
        });
    lenient().when(answerMessageRepository.save(any())).thenAnswer(invocation -> {
      AnswerMessageEntity entity = invocation.getArgument(0, AnswerMessageEntity.class);
      savedAnswerMessages.add(entity);
      return entity;
    });
    lenient().when(answerMessageRepository.findByRunIdAndMessageId(any(), any()))
        .thenAnswer(invocation -> savedAnswerMessages.stream()
            .filter(message -> message.getRunId().equals(invocation.getArgument(0, String.class)))
            .filter(message -> message.getMessageId()
                .equals(invocation.getArgument(1, String.class)))
            .findFirst());
    lenient().when(agentStepRepository.save(any())).thenAnswer(invocation -> {
      AgentStepEntity step = invocation.getArgument(0, AgentStepEntity.class);
      savedSteps.add(step);
      return step;
    });
    lenient().when(agentStepRepository.saveAndFlush(any())).thenAnswer(invocation -> {
      AgentStepEntity step = invocation.getArgument(0, AgentStepEntity.class);
      savedSteps.add(step);
      return step;
    });
    lenient().when(agentStepRepository.findTopByRunIdOrderByStepSequenceDesc(any()))
        .thenAnswer(invocation -> savedSteps.stream()
            .filter(step -> step.getRunId().equals(invocation.getArgument(0, String.class)))
            .max((left, right) -> left.getStepSequence().compareTo(right.getStepSequence())));
    lenient().when(agentStepRepository
            .findByRunIdAndStepSequenceGreaterThanOrderByStepSequenceAsc(any(), any()))
        .thenAnswer(invocation -> {
          String runId = invocation.getArgument(0, String.class);
          Long afterSequence = invocation.getArgument(1, Long.class);
          return savedSteps.stream()
              .filter(step -> step.getRunId().equals(runId))
              .filter(step -> step.getStepSequence() > afterSequence)
              .sorted((left, right) -> left.getStepSequence().compareTo(right.getStepSequence()))
              .toList();
        });
    lenient().when(agentCheckpointRepository.findByRunId(any()))
        .thenAnswer(invocation -> savedCheckpoints.stream()
            .filter(checkpoint -> checkpoint.getRunId()
                .equals(invocation.getArgument(0, String.class)))
            .findFirst());
    lenient().when(agentCheckpointRepository.save(any())).thenAnswer(invocation -> {
      AgentCheckpointEntity checkpoint = invocation.getArgument(0, AgentCheckpointEntity.class);
      if (!savedCheckpoints.contains(checkpoint)) {
        savedCheckpoints.add(checkpoint);
      }
      return checkpoint;
    });
  }

  @Test
  @DisplayName("启动 Agent 面试时返回已关联且处于 CREATED 的 Run")
  void createsAgentRunForInterviewSession() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));

    mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").isNotEmpty())
        .andExpect(jsonPath("$.data.status").value("CREATED"))
        .andExpect(jsonPath("$.data.businessSessionId").value(BUSINESS_SESSION_ID));
  }

  @Test
  @DisplayName("当前问题的回答会被接受并将 Run 推进为 RUNNING")
  void acceptsAnswerForCurrentQuestion() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-001");
    savedRun.set(waitingRun);
    String body = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-001",
        "current-question-001",
        "我会先澄清边界条件，再给出复杂度分析。"
    ));

    mockMvc.perform(post("/api/agent/runs/{runId}/messages", waitingRun.getRunId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").value(waitingRun.getRunId()))
        .andExpect(jsonPath("$.data.messageId").value("answer-message-001"))
        .andExpect(jsonPath("$.data.answeredQuestionId").value("current-question-001"))
        .andExpect(jsonPath("$.data.acceptedStatus").value("RUNNING"));

    assertThat(savedSteps).hasSize(1);
    assertThat(savedSteps.getFirst().getPreviousStatus()).isEqualTo(AgentRunStatus.WAITING_USER);
    assertThat(savedSteps.getFirst().getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(savedAnswerMessages).hasSize(1);
    assertThat(savedAnswerMessages.getFirst().getRunId()).isEqualTo(waitingRun.getRunId());
    assertThat(savedAnswerMessages.getFirst().getMessageId()).isEqualTo("answer-message-001");
    assertThat(savedAnswerMessages.getFirst().getAnsweredQuestionId())
        .isEqualTo("current-question-001");
    assertThat(savedRun.get().getStatus()).isEqualTo(AgentRunStatus.RUNNING);
  }

  @Test
  @DisplayName("相同 Answer Message 重试会复用首次受理结果")
  void reusesAcceptedAnswerForSameMessagePayload() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-001");
    savedRun.set(waitingRun);
    String body = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-retry-001",
        "current-question-001",
        "我会说明时间复杂度和空间复杂度。"
    ));

    String firstResponse = submitAnswerAndReadResponse(waitingRun.getRunId(), body);
    String retriedResponse = submitAnswerAndReadResponse(waitingRun.getRunId(), body);

    assertThat(objectMapper.readTree(retriedResponse).path("data"))
        .isEqualTo(objectMapper.readTree(firstResponse).path("data"));
    assertThat(savedAnswerMessages).hasSize(1);
    assertThat(savedSteps).hasSize(1);
  }

  @Test
  @DisplayName("条件推进失败后会复用并发获胜者已持久化的 Answer Message")
  void reusesWinningAnswerAfterLosingConditionalAdvance() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-001");
    savedRun.set(waitingRun);
    String messageId = "answer-message-concurrent-winner-001";
    String content = "并发获胜者已持久化的原始回答";
    AnswerMessageEntity winningMessage = AnswerMessageEntity.create(
        waitingRun.getRunId(),
        messageId,
        "current-question-001",
        content,
        "30413354eaab57ff3efb9c0870036ef445ee220379f31dd603605abd85b413e6"
    );
    when(answerMessageRepository.findByRunIdAndMessageId(waitingRun.getRunId(), messageId))
        .thenReturn(Optional.empty(), Optional.of(winningMessage));
    when(agentRunRepository.advanceWaitingUserToRunning(
            any(), any(), any()))
        .thenReturn(0);
    String body = objectMapper.writeValueAsString(new AnswerMessageBody(
        messageId,
        "current-question-001",
        content
    ));

    String response = submitAnswerAndReadResponse(waitingRun.getRunId(), body);

    var responseData = objectMapper.readTree(response).path("data");
    assertThat(responseData.path("runId").asText()).isEqualTo(winningMessage.getRunId());
    assertThat(responseData.path("messageId").asText()).isEqualTo(winningMessage.getMessageId());
    assertThat(responseData.path("answeredQuestionId").asText())
        .isEqualTo(winningMessage.getAnsweredQuestionId());
    assertThat(LocalDateTime.parse(responseData.path("receivedAt").asText()))
        .isEqualTo(winningMessage.getReceivedAt());
    assertThat(responseData.path("acceptedStatus").asText()).isEqualTo("RUNNING");
    assertThat(savedAnswerMessages).isEmpty();
    assertThat(savedSteps).isEmpty();
    verify(answerMessageRepository, never()).save(any());
    verify(agentStepRepository, never()).save(any());
    verify(answerMessageRepository, times(2))
        .findByRunIdAndMessageId(waitingRun.getRunId(), messageId);
  }

  @Test
  @DisplayName("同一 messageId 携带不同内容或问题时会被拒绝为幂等冲突")
  void rejectsConflictingPayloadForSameMessageId() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-001");
    savedRun.set(waitingRun);
    String acceptedBody = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-conflict-001",
        "current-question-001",
        "原始回答"
    ));
    submitAnswerAndReadResponse(waitingRun.getRunId(), acceptedBody);

    String differentContent = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-conflict-001",
        "current-question-001",
        "不同回答"
    ));
    assertAnswerMessageIdempotencyConflict(waitingRun.getRunId(), differentContent);

    String differentQuestion = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-conflict-001",
        "other-question-001",
        "原始回答"
    ));
    assertAnswerMessageIdempotencyConflict(waitingRun.getRunId(), differentQuestion);

    assertThat(savedAnswerMessages).hasSize(1);
    assertThat(savedSteps).hasSize(1);
    assertThat(savedRun.get().getStatus()).isEqualTo(AgentRunStatus.RUNNING);
  }

  @Test
  @DisplayName("RUNNING Run 会拒绝不同 messageId 的新回答且不排队")
  void rejectsNewAnswerWhenRunIsRunning() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-001");
    savedRun.set(waitingRun);
    String acceptedBody = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-running-001",
        "current-question-001",
        "第一条回答"
    ));
    submitAnswerAndReadResponse(waitingRun.getRunId(), acceptedBody);

    String newBody = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-running-002",
        "current-question-001",
        "第二条回答"
    ));
    mockMvc.perform(post("/api/agent/runs/{runId}/messages", waitingRun.getRunId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(newBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12006))
        .andExpect(jsonPath("$.message").value("Agent Run 正在执行，暂不接受新的回答"))
        .andExpect(jsonPath("$.data").doesNotExist());

    assertThat(savedAnswerMessages).hasSize(1);
    assertThat(savedSteps).hasSize(1);
    assertThat(savedRun.get().getStatus()).isEqualTo(AgentRunStatus.RUNNING);
  }

  @Test
  @DisplayName("过期问题的回答会被拒绝且不泄露当前问题")
  void rejectsAnswerForStaleQuestionWithoutPersistingAnything() throws Exception {
    AgentRunEntity waitingRun = createWaitingRun("current-question-002");
    savedRun.set(waitingRun);
    String body = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-stale-001",
        "stale-question-001",
        "这是上一题的回答"
    ));

    mockMvc.perform(post("/api/agent/runs/{runId}/messages", waitingRun.getRunId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12007))
        .andExpect(jsonPath("$.message").value("该回答对应的问题已过期，请回答当前问题"))
        .andExpect(jsonPath("$.data").doesNotExist());

    assertThat(savedAnswerMessages).isEmpty();
    assertThat(savedSteps).isEmpty();
    assertThat(savedRun.get().getStatus()).isEqualTo(AgentRunStatus.WAITING_USER);
    assertThat(savedRun.get().getCurrentQuestionId()).isEqualTo("current-question-002");
  }

  @ParameterizedTest(name = "{0} Run 会拒绝提交回答")
  @MethodSource("nonAnswerableRunStates")
  @DisplayName("暂停或终态 Run 会明确拒绝回答且不产生副作用")
  void rejectsAnswerWhenRunIsPausedOrTerminalWithoutPersistingAnything(
      AgentRunStatus runStatus,
      String expectedMessage) throws Exception {
    AgentRunEntity run = createWaitingRun("current-question-001");
    ReflectionTestUtils.setField(run, "status", runStatus);
    savedRun.set(run);
    String body = objectMapper.writeValueAsString(new AnswerMessageBody(
        "answer-message-non-answerable-" + runStatus.name().toLowerCase(),
        "current-question-001",
        "不应被接受的回答"
    ));

    mockMvc.perform(post("/api/agent/runs/{runId}/messages", run.getRunId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12004))
        .andExpect(jsonPath("$.message").value(expectedMessage))
        .andExpect(jsonPath("$.data").doesNotExist());

    assertThat(savedAnswerMessages).isEmpty();
    assertThat(savedSteps).isEmpty();
    assertThat(savedRun.get().getStatus()).isEqualTo(runStatus);
  }

  @Test
  @DisplayName("Agent 开关关闭时拒绝创建 Run")
  void rejectsAgentRunCreationWhenDisabled() throws Exception {
    agentProperties.setEnabled(false);
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));

    mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12003))
        .andExpect(jsonPath("$.message").value("Agent 功能未启用"))
        .andExpect(jsonPath("$.data").doesNotExist());

    verify(agentRunRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("创建 Run 后可通过 runId 查询同一业务关联")
  void getsCreatedRunByRunId() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String response = mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andReturn()
        .getResponse()
        .getContentAsString();
    String runId = objectMapper.readTree(response).path("data").path("runId").asText();

    mockMvc.perform(get("/api/agent/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").value(runId))
        .andExpect(jsonPath("$.data.status").value("CREATED"))
        .andExpect(jsonPath("$.data.businessSessionId").value(BUSINESS_SESSION_ID));
  }

  @Test
  @DisplayName("CREATED Run 可暂停并通过 GET 读取 PAUSED 状态")
  void pausesCreatedRunAndReturnsUpdatedState() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String runId = createRunAndReadId(body);

    mockMvc.perform(post("/api/agent/runs/{runId}/pause", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").value(runId))
        .andExpect(jsonPath("$.data.status").value("PAUSED"));

    mockMvc.perform(get("/api/agent/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAUSED"));

    assertThat(savedSteps).hasSize(1);
    assertThat(savedCheckpoints).hasSize(1);
    assertThat(savedCheckpoints.getFirst().getRunId()).isEqualTo(runId);
    assertThat(savedCheckpoints.getFirst().getLastAppliedStepSequence())
        .isEqualTo(savedSteps.getFirst().getStepSequence());
    var checkpointState = objectMapper.readTree(savedCheckpoints.getFirst().getRecoveryState());
    assertThat(checkpointState.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(checkpointState.path("lastAppliedStepSequence").asLong())
        .isEqualTo(savedSteps.getFirst().getStepSequence());
    assertThat(checkpointState.path("status").asText()).isEqualTo("PAUSED");
    assertThat(checkpointState.path("currentQuestionId").isNull()).isTrue();
  }

  @Test
  @DisplayName("非终态 Run 可取消并通过 GET 读取 CANCELLED 状态")
  void cancelsNonTerminalRunAndReturnsUpdatedState() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String runId = createRunAndReadId(body);

    mockMvc.perform(post("/api/agent/runs/{runId}/cancel", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").value(runId))
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));

    mockMvc.perform(get("/api/agent/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  @Test
  @DisplayName("终态 Run 不会被暂停重新激活")
  void doesNotReactivateTerminalRun() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String runId = createRunAndReadId(body);
    mockMvc.perform(post("/api/agent/runs/{runId}/cancel", runId))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/agent/runs/{runId}/pause", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12004))
        .andExpect(jsonPath("$.data").doesNotExist());

    mockMvc.perform(get("/api/agent/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  @ParameterizedTest(name = "Session 为 {0} 时拒绝推进 Run")
  @ValueSource(strings = {"COMPLETED", "EVALUATED"})
  @DisplayName("终态 Session 不会被 Run 状态重新激活")
  void doesNotAdvanceRunForTerminalSession(String sessionStatus) throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String runId = createRunAndReadId(body);
    InterviewSessionEntity terminalSession = new InterviewSessionEntity();
    terminalSession.setStatus(InterviewSessionEntity.SessionStatus.valueOf(sessionStatus));
    when(interviewSessionRepository.findBySessionId(BUSINESS_SESSION_ID))
        .thenReturn(Optional.of(terminalSession));

    mockMvc.perform(post("/api/agent/runs/{runId}/pause", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(3004))
        .andExpect(jsonPath("$.data").doesNotExist());

    mockMvc.perform(post("/api/agent/runs/{runId}/cancel", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(3004))
        .andExpect(jsonPath("$.data").doesNotExist());

    mockMvc.perform(get("/api/agent/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CREATED"));
  }

  @Test
  @DisplayName("成功状态变化可按 Step 序号补读为脱敏持久化事件")
  void readsCommittedStatusChangeAsSanitizedEvent() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String runId = createRunAndReadId(body);
    mockMvc.perform(post("/api/agent/runs/{runId}/pause", runId))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/agent/runs/{runId}/cancel", runId))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/agent/runs/{runId}/events", runId)
            .param("afterSequence", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].runId").value(runId))
        .andExpect(jsonPath("$.data[0].stepSequence").value(1))
        .andExpect(jsonPath("$.data[0].eventType").value("run.paused"))
        .andExpect(jsonPath("$.data[0].previousStatus").value("CREATED"))
        .andExpect(jsonPath("$.data[0].status").value("PAUSED"))
        .andExpect(jsonPath("$.data[1].stepSequence").value(2))
        .andExpect(jsonPath("$.data[1].eventType").value("run.cancelled"))
        .andExpect(jsonPath("$.data[1].previousStatus").value("PAUSED"))
        .andExpect(jsonPath("$.data[1].status").value("CANCELLED"))
        .andExpect(jsonPath("$.data[0].chainOfThought").doesNotExist())
        .andExpect(jsonPath("$.data[0].rawData").doesNotExist());

    mockMvc.perform(get("/api/agent/runs/{runId}/events", runId)
            .param("afterSequence", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].stepSequence").value(2))
        .andExpect(jsonPath("$.data[0].eventType").value("run.cancelled"));
  }

  @Test
  @DisplayName("相同幂等键重试时返回原来的 runId")
  void returnsOriginalRunForSameIdempotentRequest() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));

    String firstRunId = createRunAndReadId(body);
    String retriedRunId = createRunAndReadId(body);

    assertThat(retriedRunId).isEqualTo(firstRunId);
  }

  @Test
  @DisplayName("并发同键请求触发唯一约束时返回已创建的 Run")
  void returnsWinningRunAfterConcurrentUniqueConstraintConflict() throws Exception {
    AgentRunEntity winningRun = AgentRunEntity.create(
        AgentType.ADAPTIVE_INTERVIEWER,
        BUSINESS_SESSION_ID,
        IDEMPOTENCY_KEY,
        "485ed7f45398f9ad1ae302f696abfda9e9a9d3a021146de99ff8a6517cddd352"
    );
    when(agentRunRepository.saveAndFlush(any())).thenAnswer(invocation -> {
      savedRun.set(winningRun);
      throw new DataIntegrityViolationException("duplicate idempotency key");
    });

    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));

    mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runId").value(winningRun.getRunId()));
  }

  @Test
  @DisplayName("相同幂等键携带不同请求时返回业务冲突")
  void rejectsConflictingRequestForSameIdempotencyKey() throws Exception {
    String firstBody = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));
    String conflictingBody = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        "interview-session-002"
    ));
    createRunAndReadId(firstBody);

    mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(conflictingBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002))
        .andExpect(jsonPath("$.message").value("Idempotency-Key 已用于不同的 Agent Run 请求"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  private String createRunAndReadId(String body) throws Exception {
    String response = mockMvc.perform(post("/api/agent/runs")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
    return objectMapper.readTree(response).path("data").path("runId").asText();
  }

  private String submitAnswerAndReadResponse(String runId, String body) throws Exception {
    return mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void assertAnswerMessageIdempotencyConflict(String runId, String body) throws Exception {
    mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12005))
        .andExpect(jsonPath("$.message").value("同一 messageId 对应的 Answer Message 载荷不一致"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  private AgentRunEntity createWaitingRun(String currentQuestionId) {
    AgentRunEntity run = AgentRunEntity.create(
        AgentType.ADAPTIVE_INTERVIEWER,
        BUSINESS_SESSION_ID,
        "waiting-run-idempotency-key",
        "waiting-run-fingerprint"
    );
    ReflectionTestUtils.setField(run, "status", AgentRunStatus.WAITING_USER);
    ReflectionTestUtils.setField(run, "currentQuestionId", currentQuestionId);
    return run;
  }

  private static Stream<Arguments> nonAnswerableRunStates() {
    return Stream.of(
        Arguments.of(AgentRunStatus.PAUSED, "Agent Run 已暂停，请先恢复后再提交回答"),
        Arguments.of(AgentRunStatus.COMPLETED, "Agent Run 已完成，不能再提交回答"),
        Arguments.of(AgentRunStatus.FAILED, "Agent Run 已失败，不能再提交回答"),
        Arguments.of(AgentRunStatus.CANCELLED, "Agent Run 已取消，不能再提交回答")
    );
  }

  private record CreateRunBody(String agentType, String businessSessionId) {
  }

  private record AnswerMessageBody(String messageId, String questionId, String content) {
  }
}
