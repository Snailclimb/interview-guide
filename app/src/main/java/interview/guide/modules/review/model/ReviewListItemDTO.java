package interview.guide.modules.review.model;

import java.time.LocalDateTime;

/**
 * 面试复盘列表项 DTO（轻量，不含大字段）
 */
public record ReviewListItemDTO(
    Long id,
    String companyName,
    String position,
    Integer roundNumber,
    LocalDateTime interviewDate,
    ReviewStatus status,
    LocalDateTime createdAt,
    boolean questionAnalysisDone,
    boolean projectAnalysisDone,
    boolean questionRecordDone
) {}
