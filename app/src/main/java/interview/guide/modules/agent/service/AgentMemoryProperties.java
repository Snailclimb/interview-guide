package interview.guide.modules.agent.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Agent 长期记忆的轻量生命周期策略。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.agent.memory")
public class AgentMemoryProperties {

    /** 是否启用自动过期；关闭时保留历史行为。 */
    private boolean expirationEnabled = true;

    /** 记忆从最近更新时间起的保留天数。 */
    @Min(value = 1, message = "Agent 长期记忆保留天数必须大于 0")
    @Max(value = 3650, message = "Agent 长期记忆保留天数不能超过 3650 天")
    private int retentionDays = 180;
}
