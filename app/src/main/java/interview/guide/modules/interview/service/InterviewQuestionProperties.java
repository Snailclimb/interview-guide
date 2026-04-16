package interview.guide.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    private int followUpCount = 1;
    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";
}
