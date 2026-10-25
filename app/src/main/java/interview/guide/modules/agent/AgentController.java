package interview.guide.modules.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentConversationSummary;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.model.CreateAgentConversationRequest;
import interview.guide.modules.agent.model.RenameAgentConversationRequest;
import interview.guide.modules.agent.service.AgentConversationService;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.InterviewAgentService;
import interview.guide.modules.auth.AuthIdentityService;
import interview.guide.common.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Agent 接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "AI 面试教练", description = "基于面试报告的 Agent 复盘与训练对话")
public class AgentController {
    private final InterviewAgentService agentService;
    private final AgentConversationService conversationService;
    private final AgentMemoryService memoryService;
    private final AuthIdentityService authIdentityService;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/agent/interview-sessions/{sessionId}/conversation")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentConversationResponse> getConversation(
        @PathVariable String sessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey,
        @RequestParam(value = "agentSessionId", required = false) String agentSessionId
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(conversationService.getConversation(sessionId, agentSessionId, identity));
    }

    @GetMapping("/api/agent/interview-sessions/{sessionId}/conversations")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentConversationSummary>> listConversations(
        @PathVariable String sessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.listConversations(sessionId, identity));
    }

    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> createConversation(
        @PathVariable String sessionId,
        @Valid @RequestBody(required = false) CreateAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.createConversation(
            sessionId, request == null ? null : request.title(), identity
        ));
    }

    @PatchMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> renameConversation(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody RenameAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.renameConversation(
            sessionId, agentSessionId, request.title(), identity
        ));
    }

    @DeleteMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> archiveConversation(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.archiveConversation(sessionId, agentSessionId, identity);
        return Result.success(null);
    }

    @PostMapping("/api/agent/interview-sessions/{sessionId}/cancel")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationResponse> cancelRun(
        @PathVariable String sessionId,
        @RequestParam(value = "agentSessionId", required = false) String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        agentService.cancel(agentSessionId == null || agentSessionId.isBlank() ? sessionId : agentSessionId);
        conversationService.cancelRun(sessionId, agentSessionId, identity);
        return Result.success(conversationService.getConversation(sessionId, agentSessionId, identity));
    }

    @DeleteMapping("/api/agent/memory")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> clearMemory(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        memoryService.clear(authIdentityService.requireIdentity(ownerKey));
        return Result.success(null);
    }

    /**
     * 基于指定面试报告执行一次 Agent 任务，并流式返回工作流事件和模型回答。
     */
    @PostMapping(value = "/api/agent/interview-sessions/{sessionId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamMessage(
        @PathVariable String sessionId,
        @Valid @RequestBody AgentMessageRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        log.info("收到面试教练 Agent 请求: sessionId={}, goal={}, messageLength={}",
            sessionId, request.goal(), request.message().length());
        return agentService.stream(sessionId, request, identity)
            .map(event -> ServerSentEvent.<AgentStreamEvent>builder()
                // Keep the transport event name distinct from the generic SSE error event.
                .event("agent-" + event.type())
                .data(event)
                .build());
    }

    /**
     * Agent 是 SSE 接口，参数校验失败时也必须返回 SSE 事件，避免全局 JSON 异常响应与
     * text/event-stream 的 Accept 头冲突，导致浏览器只能看到 HTTP 400。
     */
    @ExceptionHandler({WebExchangeBindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<String> handleValidationException(
        Exception error
    ) {
        List<FieldError> fieldErrors;
        if (error instanceof WebExchangeBindException bindException) {
            fieldErrors = bindException.getFieldErrors();
        } else if (error instanceof MethodArgumentNotValidException argumentNotValidException) {
            fieldErrors = argumentNotValidException.getBindingResult().getFieldErrors();
        } else {
            fieldErrors = List.of();
        }

        String message = fieldErrors.stream()
            .map(fieldError -> fieldError.getDefaultMessage() == null
                ? "请求参数不合法"
                : fieldError.getDefaultMessage())
            .distinct()
            .collect(Collectors.joining("；"));
        String errorMessage = message.isBlank() ? "请求参数不合法" : message;
        try {
            String eventData = objectMapper.writeValueAsString(AgentStreamEvent.error(errorMessage));
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: agent-error\ndata: " + eventData + "\n\n");
        } catch (JsonProcessingException serializationError) {
            log.warn("无法序列化 Agent 参数校验 SSE 错误", serializationError);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: agent-error\ndata: {\"type\":\"error\",\"message\":\"请求参数不合法\"}\n\n");
        }
    }
}
