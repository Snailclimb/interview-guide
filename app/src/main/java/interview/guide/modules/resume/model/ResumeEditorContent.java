package interview.guide.modules.resume.model;

import jakarta.validation.constraints.Size;

/** 可编辑的结构化简历内容。文本字段保留换行，便于手动维护项目和经历。 */
public record ResumeEditorContent(
    @Size(max = 200, message = "姓名不能超过200个字符") String name,
    @Size(max = 200, message = "邮箱不能超过200个字符") String email,
    @Size(max = 80, message = "手机号不能超过80个字符") String phone,
    @Size(max = 200, message = "所在地不能超过200个字符") String location,
    @Size(max = 200, message = "求职意向不能超过200个字符") String targetPosition,
    @Size(max = 12000, message = "专业技能不能超过12000个字符") String skills,
    @Size(max = 20000, message = "工作经历不能超过20000个字符") String workExperience,
    @Size(max = 24000, message = "项目经历不能超过24000个字符") String projects,
    @Size(max = 12000, message = "教育经历不能超过12000个字符") String education,
    @Size(max = 8000, message = "个人评价不能超过8000个字符") String selfEvaluation,
    @Size(max = 60000, message = "原始简历文本不能超过60000个字符") String rawText
) {
    public boolean isEmpty() {
        return blank(name) && blank(email) && blank(phone) && blank(location)
            && blank(targetPosition) && blank(skills) && blank(workExperience)
            && blank(projects) && blank(education) && blank(selfEvaluation) && blank(rawText);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
