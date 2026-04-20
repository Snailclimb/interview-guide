package interview.guide.modules.review.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试复盘详情 DTO
 */
public record ReviewDetailDTO(
    Long id,
    Long scheduleId,
    String transcriptStorageUrl,
    String transcriptText,
    String companyName,
    String position,
    Integer roundNumber,
    LocalDateTime interviewDate,
    ReviewStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<ArtifactDTO> artifacts
) {}
