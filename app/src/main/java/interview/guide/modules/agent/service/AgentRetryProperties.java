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

    /** 一次 Agent 运行最多发起的模型请求数，包含规划、重试、压缩重试和备用 Provider。 */
    @Min(value = 1, message = "Agent 单次运行模型调用预算必须大于 0")
    @Max(value = 32, message = "Agent 单次运行模型调用预算不能超过 32 次")
    private int maxModelCallsPerRun = 12;

    /** 一次 Agent 运行最多执行的只读工具调用数。 */
    @Min(value = 0, message = "Agent 单次运行工具调用预算不能为负数")
    @Max(value = 64, message = "Agent 单次运行工具调用预算不能超过 64 次")
    private int maxToolCallsPerRun = 8;

    /** 是否启用受控 Agent Loop；关闭时回退到现有单次回答流程。 */
    private boolean loopEnabled = true;

    /** 一次 Agent Loop 最多展示和执行的规划步骤数，避免规划无限膨胀。 */
    @Min(value = 1, message = "Agent Loop 步骤数必须大于 0")
    @Max(value = 3, message = "Agent Loop 步骤数不能超过 3")
    private int loopMaxSteps = 3;

    /** 单个 Loop 步骤的最大执行时间（毫秒）。 */
    @Min(value = 100, message = "Agent Loop 步骤超时不能小于 100 毫秒")
    @Max(value = 60000, message = "Agent Loop 步骤超时不能超过 60000 毫秒")
    private long loopStepTimeoutMillis = 5000;

    /** 单个 Loop 步骤失败后的重试次数。 */
    @Min(value = 0, message = "Agent Loop 步骤重试次数不能为负数")
    @Max(value = 3, message = "Agent Loop 步骤重试次数不能超过 3 次")
    private int loopStepRetryAttempts = 1;

    /** 一次 Loop 的总执行时间预算（毫秒）。 */
    @Min(value = 100, message = "Agent Loop 总超时不能小于 100 毫秒")
    @Max(value = 180000, message = "Agent Loop 总超时不能超过 180000 毫秒")
    private long loopTotalTimeoutMillis = 15000;

    /** 启动恢复扫描是否启用。 */
    private boolean recoveryEnabled = true;

    /** 运行超过该时长仍为 RUNNING 才允许被新实例接管。 */
    @Min(value = 10, message = "Agent 恢复最小年龄不能小于 10 秒")
    @Max(value = 3600, message = "Agent 恢复最小年龄不能超过 3600 秒")
    private int recoveryMinAgeSeconds = 30;

}
