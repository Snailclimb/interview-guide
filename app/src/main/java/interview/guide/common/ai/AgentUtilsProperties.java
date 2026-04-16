package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.agent-utils")
public class AgentUtilsProperties {

    private String skillsRoot = "classpath:skills";
}
