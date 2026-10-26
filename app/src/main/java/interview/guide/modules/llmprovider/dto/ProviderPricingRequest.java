package interview.guide.modules.llmprovider.dto;

import java.math.BigDecimal;

/** 更新 Provider 的数据库价格覆盖；两个价格都为 null 时清除覆盖。 */
public record ProviderPricingRequest(
    BigDecimal inputPerMillion,
    BigDecimal outputPerMillion,
    String currency
) {
}
