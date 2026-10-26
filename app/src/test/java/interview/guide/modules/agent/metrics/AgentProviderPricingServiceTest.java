package interview.guide.modules.agent.metrics;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.llmprovider.dto.ProviderPricingRequest;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Provider 模型费用管理")
class AgentProviderPricingServiceTest {

    @Mock
    private LlmProviderRepository providerRepository;

    @Test
    @DisplayName("数据库没有覆盖值时沿用配置文件价格")
    void fallsBackToConfiguredPrice() {
        AgentCostProperties properties = configuredProperties();
        when(providerRepository.findById("pixel")).thenReturn(Optional.empty());

        AgentProviderPricingService service = new AgentProviderPricingService(properties, providerRepository);

        assertThat(service.describe("pixel").source()).isEqualTo("config");
        assertThat(service.find("pixel").getInputPerMillion()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("数据库覆盖值优先于配置，并且清除后回退")
    void databaseOverrideCanBeUpdatedAndCleared() {
        AgentCostProperties properties = configuredProperties();
        LlmProviderEntity entity = LlmProviderEntity.builder().id("pixel").build();
        when(providerRepository.findById("pixel")).thenReturn(Optional.of(entity));

        AgentProviderPricingService service = new AgentProviderPricingService(properties, providerRepository);
        service.update("pixel", new ProviderPricingRequest(
            new BigDecimal("3"), new BigDecimal("9"), "cny"));

        assertThat(service.describe("pixel").source()).isEqualTo("database");
        assertThat(service.find("pixel").getOutputPerMillion()).isEqualByComparingTo("9");
        assertThat(entity.getPriceCurrency()).isEqualTo("CNY");
        verify(providerRepository).save(entity);

        service.update("pixel", new ProviderPricingRequest(null, null, "USD"));

        assertThat(service.describe("pixel").source()).isEqualTo("config");
        assertThat(service.find("pixel").getInputPerMillion()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("拒绝负价格和过高小数精度")
    void rejectsInvalidPrice() {
        AgentProviderPricingService service = new AgentProviderPricingService(
            configuredProperties(), providerRepository);

        assertThatThrownBy(() -> service.update("pixel",
            new ProviderPricingRequest(new BigDecimal("-1"), null, "USD")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非负数");

        assertThatThrownBy(() -> service.update("pixel",
            new ProviderPricingRequest(new BigDecimal("1.123456789"), null, "USD")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("8 位小数");
    }

    private AgentCostProperties configuredProperties() {
        AgentCostProperties properties = new AgentCostProperties();
        AgentCostProperties.Pricing pricing = new AgentCostProperties.Pricing();
        pricing.setInputPerMillion(new BigDecimal("2"));
        pricing.setOutputPerMillion(new BigDecimal("6"));
        pricing.setCurrency("USD");
        properties.getProviders().put("pixel", pricing);
        return properties;
    }
}
