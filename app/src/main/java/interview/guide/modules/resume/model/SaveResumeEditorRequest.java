package interview.guide.modules.resume.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SaveResumeEditorRequest(
    @NotNull(message = "简历编辑内容不能为空") @Valid ResumeEditorContent content
) {}
