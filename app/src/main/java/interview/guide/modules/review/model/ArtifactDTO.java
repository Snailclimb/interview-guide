package interview.guide.modules.review.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 分析成果 DTO
 */
public record ArtifactDTO(
    Long id,
    ArtifactType type,
    String content,
    Integer version,
    AsyncTaskStatus status,
    String error,
    LocalDateTime updatedAt
) {}
