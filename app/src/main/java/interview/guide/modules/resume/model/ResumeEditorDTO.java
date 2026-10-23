package interview.guide.modules.resume.model;

import java.time.LocalDateTime;

/** 简历编辑器的当前草稿。 */
public record ResumeEditorDTO(
    Long resumeId,
    Integer version,
    ResumeEditorSource source,
    LocalDateTime updatedAt,
    ResumeEditorContent content,
    String originalText,
    boolean appliedToInterview,
    boolean analysisOutdated
) {}
