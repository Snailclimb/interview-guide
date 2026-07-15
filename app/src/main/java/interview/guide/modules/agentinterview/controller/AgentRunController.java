package interview.guide.modules.agentinterview.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.agentinterview.model.AgentRunResponse;
import interview.guide.modules.agentinterview.model.AnswerMessageResponse;
import interview.guide.modules.agentinterview.model.CreateAgentRunRequest;
import interview.guide.modules.agentinterview.model.AgentRunEventResponse;
import interview.guide.modules.agentinterview.model.SubmitAnswerMessageRequest;
import interview.guide.modules.agentinterview.service.AgentRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AgentRunController {

  private final AgentRunService agentRunService;

  @PostMapping("/api/agent/runs")
  public Result<AgentRunResponse> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateAgentRunRequest request) {
    return Result.success(agentRunService.create(idempotencyKey, request));
  }

  @GetMapping("/api/agent/runs/{runId}")
  public Result<AgentRunResponse> get(@PathVariable String runId) {
    return Result.success(agentRunService.get(runId));
  }

  @PostMapping("/api/agent/runs/{runId}/pause")
  public Result<AgentRunResponse> pause(@PathVariable String runId) {
    return Result.success(agentRunService.pause(runId));
  }

  @PostMapping("/api/agent/runs/{runId}/cancel")
  public Result<AgentRunResponse> cancel(@PathVariable String runId) {
    return Result.success(agentRunService.cancel(runId));
  }

  @PostMapping("/api/agent/runs/{runId}/messages")
  public Result<AnswerMessageResponse> submitAnswer(
      @PathVariable String runId,
      @Valid @RequestBody SubmitAnswerMessageRequest request) {
    return Result.success(agentRunService.submitAnswer(runId, request));
  }

  @GetMapping("/api/agent/runs/{runId}/events")
  public Result<List<AgentRunEventResponse>> getEvents(
      @PathVariable String runId,
      @RequestParam(defaultValue = "0") long afterSequence) {
    return Result.success(agentRunService.getEvents(runId, afterSequence));
  }
}
