package interview.guide.modules.agentinterview.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.agentinterview.model.AgentRunResponse;
import interview.guide.modules.agentinterview.model.CreateAgentRunRequest;
import interview.guide.modules.agentinterview.service.AgentRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

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
}
