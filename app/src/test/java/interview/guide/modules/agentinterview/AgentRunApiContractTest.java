package interview.guide.modules.agentinterview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import interview.guide.common.agent.config.AgentProperties;
import interview.guide.common.agent.runtime.AgentType;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import interview.guide.infrastructure.agent.persistence.AgentStepEntity;
import interview.guide.infrastructure.agent.persistence.AgentStepRepository;
import interview.guide.modules.agentinterview.controller.AgentRunController;
import interview.guide.modules.agentinterview.service.AgentRunService;
import interview.guide.modules.agentinterview.service.AgentRunPersistenceService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
  private AgentStepRepository agentStepRepository;

  @Mock
  private InterviewSessionRepository interviewSessionRepository;

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private AtomicReference<AgentRunEntity> savedRun;
  private List<AgentStepEntity> savedSteps;
  private AgentProperties agentProperties;

  @BeforeEach
  void setUp() {
    AgentRunPersistenceService persistenceService = new AgentRunPersistenceService(agentRunRepository);
    agentProperties = new AgentProperties();
    agentProperties.setEnabled(true);
    AgentRunService service = new AgentRunService(
        agentRunRepository,
        agentStepRepository,
        interviewSessionRepository,
        persistenceService,
        agentProperties
    );
    AgentRunController controller = new AgentRunController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    savedRun = new AtomicReference<>();
    savedSteps = new ArrayList<>();

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
    lenient().when(agentStepRepository.save(any())).thenAnswer(invocation -> {
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

  private record CreateRunBody(String agentType, String businessSessionId) {
  }
}
