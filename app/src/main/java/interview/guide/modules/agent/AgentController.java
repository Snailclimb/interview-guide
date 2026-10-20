package interview.guide.modules.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.service.InterviewAgentService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI Agent 接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "AI 面试教练", description = "基于面试报告的 Agent 复盘与训练对话")
public class AgentController {
    private final InterviewAgentService agentService;

    /**
     * 基于指定面试报告执行一次 Agent 任务，并流式返回工作流事件和模型回答。
     */
    @PostMapping(value = "/api/agent/interview-sessions/{sessionId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamMessage(
        @PathVariable String sessionId,
        @Valid @RequestBody AgentMessageRequest request
    ) {
        log.info("收到面试教练 Agent 请求: sessionId={}, goal={}, messageLength={}",
            sessionId, request.goal(), request.message().length());
        return agentService.stream(sessionId, request)
            .map(event -> ServerSentEvent.<AgentStreamEvent>builder()
                // Keep the transport event name distinct from the generic SSE error event.
                .event("agent-" + event.type())
                .data(event)
                .build());
    }
}
