package interview.guide.modules.agentinterview.service;

import interview.guide.common.agent.runtime.AgentType;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import interview.guide.modules.agentinterview.model.AgentRunResponse;
import interview.guide.modules.agentinterview.model.CreateAgentRunRequest;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AgentRunService {

  private final AgentRunRepository agentRunRepository;
  private final InterviewSessionRepository interviewSessionRepository;

  @Transactional
  public AgentRunResponse create(String idempotencyKey, CreateAgentRunRequest request) {
    String businessSessionId = request.businessSessionId().trim();
    AgentType agentType = parseAgentType(request.agentType());
    String normalizedKey = idempotencyKey.trim();
    String requestFingerprint = fingerprint(agentType, businessSessionId);
    var existingRun = agentRunRepository.findByIdempotencyKey(normalizedKey);
    if (existingRun.isPresent()) {
      if (!existingRun.get().getRequestFingerprint().equals(requestFingerprint)) {
        throw new BusinessException(
            ErrorCode.AGENT_IDEMPOTENCY_CONFLICT,
            "Idempotency-Key 已用于不同的 Agent Run 请求"
        );
      }
      return AgentRunResponse.from(existingRun.get());
    }

    if (interviewSessionRepository.findBySessionId(businessSessionId).isEmpty()) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
          "面试会话不存在: " + businessSessionId
      );
    }

    AgentRunEntity entity = AgentRunEntity.create(
        agentType,
        businessSessionId,
        normalizedKey,
        requestFingerprint
    );
    return AgentRunResponse.from(agentRunRepository.save(entity));
  }

  @Transactional(readOnly = true)
  public AgentRunResponse get(String runId) {
    AgentRunEntity entity = agentRunRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AGENT_RUN_NOT_FOUND,
            "Agent Run 不存在: " + runId
        ));
    return AgentRunResponse.from(entity);
  }

  private AgentType parseAgentType(String value) {
    try {
      return AgentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 Agent 类型: " + value);
    }
  }

  private String fingerprint(AgentType agentType, String businessSessionId) {
    String canonicalRequest = agentType.name() + ":" + businessSessionId;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
    }
  }
}
