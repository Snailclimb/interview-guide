package interview.guide.modules.agentinterview.service;

import interview.guide.common.agent.config.AgentProperties;
import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.common.agent.runtime.AgentType;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import interview.guide.infrastructure.agent.persistence.AgentStepEntity;
import interview.guide.infrastructure.agent.persistence.AgentStepRepository;
import interview.guide.modules.agentinterview.model.AgentRunEventResponse;
import interview.guide.modules.agentinterview.model.AgentRunResponse;
import interview.guide.modules.agentinterview.model.CreateAgentRunRequest;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AgentRunService {

  private final AgentRunRepository agentRunRepository;
  private final AgentStepRepository agentStepRepository;
  private final InterviewSessionRepository interviewSessionRepository;
  private final AgentRunPersistenceService persistenceService;
  private final AgentProperties agentProperties;

  public AgentRunResponse create(String idempotencyKey, CreateAgentRunRequest request) {
    if (!agentProperties.isEnabled()) {
      throw new BusinessException(ErrorCode.AGENT_DISABLED, "Agent 功能未启用");
    }

    String businessSessionId = request.businessSessionId().trim();
    AgentType agentType = parseAgentType(request.agentType());
    String normalizedKey = idempotencyKey.trim();
    String requestFingerprint = fingerprint(agentType, businessSessionId);
    var existingRun = agentRunRepository.findByIdempotencyKey(normalizedKey);
    if (existingRun.isPresent()) {
      return resolveIdempotentRequest(existingRun.get(), requestFingerprint);
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
    try {
      return AgentRunResponse.from(persistenceService.create(entity));
    } catch (DataIntegrityViolationException exception) {
      AgentRunEntity winningRun = agentRunRepository.findByIdempotencyKey(normalizedKey)
          .orElseThrow(() -> exception);
      return resolveIdempotentRequest(winningRun, requestFingerprint);
    }
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

  @Transactional
  public AgentRunResponse pause(String runId) {
    AgentRunEntity entity = findRun(runId);
    ensureSessionCanAdvance(entity);
    var previousStatus = entity.getStatus();
    if (!entity.pause()) {
      throw new BusinessException(
          ErrorCode.AGENT_INVALID_STATE_TRANSITION,
          "当前 Agent Run 状态不允许暂停: " + entity.getStatus()
      );
    }
    persistStatusChange(entity, previousStatus);
    return AgentRunResponse.from(entity);
  }

  @Transactional
  public AgentRunResponse cancel(String runId) {
    AgentRunEntity entity = findRun(runId);
    ensureSessionCanAdvance(entity);
    var previousStatus = entity.getStatus();
    if (!entity.cancel()) {
      throw new BusinessException(
          ErrorCode.AGENT_INVALID_STATE_TRANSITION,
          "当前 Agent Run 状态不允许取消: " + entity.getStatus()
      );
    }
    persistStatusChange(entity, previousStatus);
    return AgentRunResponse.from(entity);
  }

  @Transactional(readOnly = true)
  public List<AgentRunEventResponse> getEvents(String runId, long afterSequence) {
    findRun(runId);
    if (afterSequence < 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "afterSequence 不能小于 0");
    }
    return agentStepRepository
        .findByRunIdAndStepSequenceGreaterThanOrderByStepSequenceAsc(runId, afterSequence)
        .stream()
        .map(AgentRunEventResponse::from)
        .toList();
  }

  private void persistStatusChange(
      AgentRunEntity run,
      AgentRunStatus previousStatus) {
    long nextSequence = agentStepRepository.findTopByRunIdOrderByStepSequenceDesc(run.getRunId())
        .map(AgentStepEntity::getStepSequence)
        .orElse(0L) + 1;
    agentStepRepository.save(AgentStepEntity.statusChanged(
        run.getRunId(),
        nextSequence,
        previousStatus,
        run.getStatus()
    ));
  }

  private void ensureSessionCanAdvance(AgentRunEntity run) {
    InterviewSessionEntity session = interviewSessionRepository
        .findBySessionId(run.getBusinessSessionId())
        .orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
            "面试会话不存在: " + run.getBusinessSessionId()
        ));
    if (session.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
        || session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_ALREADY_COMPLETED,
          "终态面试会话不允许推进 Agent Run: " + session.getStatus()
      );
    }
  }

  private AgentRunEntity findRun(String runId) {
    return agentRunRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AGENT_RUN_NOT_FOUND,
            "Agent Run 不存在: " + runId
        ));
  }

  private AgentType parseAgentType(String value) {
    try {
      return AgentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 Agent 类型: " + value);
    }
  }

  private AgentRunResponse resolveIdempotentRequest(
      AgentRunEntity existingRun,
      String requestFingerprint) {
    if (!existingRun.getRequestFingerprint().equals(requestFingerprint)) {
      throw new BusinessException(
          ErrorCode.AGENT_IDEMPOTENCY_CONFLICT,
          "Idempotency-Key 已用于不同的 Agent Run 请求"
      );
    }
    return AgentRunResponse.from(existingRun);
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
