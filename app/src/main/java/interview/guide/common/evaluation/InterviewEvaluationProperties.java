package interview.guide.common.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    private int batchSize = 8;
    private String systemPromptPath = "classpath:prompts/interview-evaluation-system.st";
    private String userPromptPath = "classpath:prompts/interview-evaluation-user.st";
    private String summarySystemPromptPath = "classpath:prompts/interview-evaluation-summary-system.st";
    private String summaryUserPromptPath = "classpath:prompts/interview-evaluation-summary-user.st";
}
