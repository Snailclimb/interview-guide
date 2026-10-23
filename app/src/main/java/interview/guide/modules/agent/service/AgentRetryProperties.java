package interview.guide.modules.agent.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 模型调用的等待与重试策略。
 *
 * <p>把等待策略放到配置中，便于按模型供应商的限流和部署环境调整；默认值保持
 * 当前行为，避免新增配置后改变现有面试流程。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.agent")
public class AgentRetryProperties {

    /** 初次调用失败后最多重试次数。0 表示不重试。 */
    @Min(value = 0, message = "Agent 模型重试次数不能为负数")
    @Max(value = 12, message = "Agent 模型重试次数不能超过 12 次")
    private int modelRetryAttempts = 6;

    /** 指数退避的单次等待上限（秒）。 */
    @Min(value = 1, message = "Agent 重试最长等待必须大于 0 秒")
    @Max(value = 120, message = "Agent 重试最长等待不能超过 120 秒")
    private int retryMaxDelaySeconds = 30;

    /** 模型等待首 token 或重试等待期间 SSE 心跳的间隔（秒）。 */
    @Min(value = 1, message = "Agent 重试心跳间隔必须大于 0 秒")
    @Max(value = 60, message = "Agent 重试心跳间隔不能超过 60 秒")
    private int retryHeartbeatIntervalSeconds = 10;

}
