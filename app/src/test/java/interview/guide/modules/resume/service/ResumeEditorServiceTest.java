package interview.guide.modules.resume.service;

import interview.guide.modules.resume.model.ResumeEditorContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeEditorServiceTest {

    @Test
    void composeTextKeepsStructuredSectionsReadableForFutureInterviews() {
        ResumeEditorContent content = new ResumeEditorContent(
            "张三", "zhang@example.com", "13800000000", "北京", "Java 后端开发",
            "Java\nSpring Boot\nMySQL", "某公司 Java 工程师", "订单系统优化\nRT 从 2s 降到 200ms",
            "某大学 计算机科学与技术", "关注高并发和稳定性", null
        );

        String text = ResumeEditorService.composeText(content);

        assertThat(text)
            .contains("## 基本信息")
            .contains("张三 | zhang@example.com | 13800000000 | 北京 | Java 后端开发")
            .contains("## 专业技能\nJava")
            .contains("## 项目经历\n订单系统优化")
            .contains("## 个人评价\n关注高并发和稳定性");
    }

    @Test
    void composeTextFallsBackToRawTextWhenNoSectionWasStructured() {
        ResumeEditorContent content = new ResumeEditorContent(
            null, null, null, null, null, null, null, null, null, null,
            "这是尚未结构化的简历原文。"
        );

        assertThat(ResumeEditorService.composeText(content)).isEqualTo("这是尚未结构化的简历原文。");
    }

    @Test
    void composeTextDoesNotUseRawTextAfterStructuredEditingStarts() {
        ResumeEditorContent content = new ResumeEditorContent(
            "李四", null, null, null, null, null, null, "新的项目经历", null, null,
            "旧的原始文本"
        );

        assertThat(ResumeEditorService.composeText(content))
            .contains("新的项目经历")
            .doesNotContain("旧的原始文本");
    }
}
