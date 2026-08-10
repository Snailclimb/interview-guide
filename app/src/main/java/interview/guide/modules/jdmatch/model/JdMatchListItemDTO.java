package interview.guide.modules.jdmatch.model;

import java.time.LocalDateTime;

/**
 * JD 匹配分析列表项 DTO
 */
public record JdMatchListItemDTO(
    Long id,
    Long resumeId,
    String resumeFilename,
    String jdTitle,
    Integer overallScore,
    String status,
    String error,
    LocalDateTime createdAt
) {
}
