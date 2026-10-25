package interview.guide.modules.agent.metrics;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 模型费用配置。
 *
 * <p>单价是可选配置，单位为每百万 Token。默认不配置任何单价，因此系统只
 * 记录 Provider 返回的 Token，不会根据未知价格猜测费用。Provider 配置暂时
 * 通过 application.yml / 环境变量维护，后续再接入模型管理页。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.agent.cost")
public class AgentCostProperties {

    /** 是否发布费用指标。关闭后仍保留 Token 和耗时指标。 */
    private boolean enabled = true;

    /** Provider ID 到单价的配置，key 只允许使用低基数 Provider ID。 */
    private Map<String, Pricing> providers = new LinkedHashMap<>();

    public Pricing find(String providerId) {
        if (providerId == null || providers == null) {
            return null;
        }
        return providers.get(providerId);
    }

    @Data
    public static class Pricing {
        /** 输入 Token 每百万的价格。 */
        private BigDecimal inputPerMillion;

        /** 输出 Token 每百万的价格。 */
        private BigDecimal outputPerMillion;

        /** 费用币种，默认 USD。 */
        private String currency = "USD";

        public boolean configured() {
            return positive(inputPerMillion) || positive(outputPerMillion);
        }

        public BigDecimal calculate(int promptTokens, int completionTokens) {
            BigDecimal input = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
            BigDecimal output = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
            return input.multiply(BigDecimal.valueOf(Math.max(0, promptTokens)))
                .add(output.multiply(BigDecimal.valueOf(Math.max(0, completionTokens))))
                .divide(BigDecimal.valueOf(1_000_000));
        }

        private boolean positive(BigDecimal value) {
            return value != null && value.signum() > 0;
        }
    }
}
