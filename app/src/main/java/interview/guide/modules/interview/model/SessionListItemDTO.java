package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）
 */
public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String title,
    String difficulty,
    Long resumeId,
    int totalQuestions,
    SessionStatus status,
    AsyncTaskStatus evaluateStatus,
    String evaluateError,
    Integer overallScore,
    String sourceType,
    Long knowledgeBaseId,
    String interviewCategory,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
            e.getSessionId(),
            e.getSkillId(),
            e.getTitle(),
            e.getDifficulty(),
            e.getResumeId(),
            e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
            e.getStatus(),
            e.getEvaluateStatus(),
            e.getEvaluateError(),
            e.getOverallScore(),
            e.getSourceType(),
            e.getKnowledgeBaseId(),
            e.getInterviewCategory(),
            e.getCreatedAt(),
            e.getCompletedAt()
        );
    }
}
