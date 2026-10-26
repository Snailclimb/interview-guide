package interview.guide.modules.llmprovider.dto;

import java.math.BigDecimal;

/** Provider 的有效模型单价，单位为每百万 Token。 */
public record ProviderPricingDTO(
    BigDecimal inputPerMillion,
    BigDecimal outputPerMillion,
    String currency,
    boolean configured,
    String source
) {
}
