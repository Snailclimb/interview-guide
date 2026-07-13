package interview.guide.modules.agentinterview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import interview.guide.modules.agentinterview.controller.AgentRunController;
import interview.guide.modules.agentinterview.service.AgentRunService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Run API 集成测试")
class AgentRunApiIntegrationTest {

  private static final String BUSINESS_SESSION_ID = "interview-session-001";
  private static final String IDEMPOTENCY_KEY = "start-agent-interview-001";

  @Mock
  private AgentRunRepository agentRunRepository;

  @Mock
  private InterviewSessionRepository interviewSessionRepository;

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private AtomicReference<AgentRunEntity> savedRun;

  @BeforeEach
  void setUp() {
    AgentRunService service = new AgentRunService(agentRunRepository, interviewSessionRepository);
    AgentRunController controller = new AgentRunController(service);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    savedRun = new AtomicReference<>();

    when(interviewSessionRepository.findBySessionId(BUSINESS_SESSION_ID))
        .thenReturn(Optional.of(new InterviewSessionEntity()));
    when(agentRunRepository.save(any())).thenAnswer(invocation -> {
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
  @DisplayName("相同幂等键重试时返回原来的 runId")
  void returnsOriginalRunForSameIdempotentRequest() throws Exception {
    String body = objectMapper.writeValueAsString(new CreateRunBody(
        "ADAPTIVE_INTERVIEWER",
        BUSINESS_SESSION_ID
    ));

    String firstRunId = createRunAndReadId(body);
    String retriedRunId = createRunAndReadId(body);

    assertEquals(firstRunId, retriedRunId);
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
