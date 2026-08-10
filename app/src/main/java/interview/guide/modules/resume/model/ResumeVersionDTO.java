package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 简历版本摘要DTO（版本链中每一项）
 */
public record ResumeVersionDTO(
    Long id,
    Integer versionNo,
    String filename,
    LocalDateTime uploadedAt,
    Integer latestScore,
    LocalDateTime lastAnalyzedAt,
    AsyncTaskStatus analyzeStatus,
    String versionNote,
    boolean current
) {}
