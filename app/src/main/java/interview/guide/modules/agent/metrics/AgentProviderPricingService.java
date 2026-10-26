package interview.guide.modules.agent.metrics;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.ProviderPricingDTO;
import interview.guide.modules.llmprovider.dto.ProviderPricingRequest;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves model prices at runtime. A database override is preferred; when no
 * override exists, the existing application configuration remains the fallback.
 */
@Service
public class AgentProviderPricingService {

    private final AgentCostProperties costProperties;
    private final LlmProviderRepository providerRepository;
    private final Map<String, Optional<ResolvedPricing>> cache = new ConcurrentHashMap<>();

    @Autowired
    public AgentProviderPricingService(
        AgentCostProperties costProperties,
        @Autowired(required = false) LlmProviderRepository providerRepository
    ) {
        this.costProperties = costProperties == null ? new AgentCostProperties() : costProperties;
        this.providerRepository = providerRepository;
    }

    /** Resolve the effective price for metrics and run usage aggregation. */
    public AgentCostProperties.Pricing find(String providerId) {
        return resolve(providerId).map(ResolvedPricing::pricing).orElse(null);
    }

    /** Return the effective price and its source for the settings page. */
    public ProviderPricingDTO describe(String providerId) {
        return resolve(providerId)
            .map(value -> toDto(value.pricing(), value.source()))
            .orElseGet(() -> new ProviderPricingDTO(null, null, "USD", false, "none"));
    }

    /** Persist a database override, or update the legacy in-memory fallback. */
    @Transactional
    public ProviderPricingDTO update(String providerId, ProviderPricingRequest request) {
        String id = requireProviderId(providerId);
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "价格配置不能为空");
        }
        validate(request);

        if (providerRepository != null) {
            LlmProviderEntity provider = providerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                    "Provider '" + id + "' 不存在"));
            provider.setInputPricePerMillion(request.inputPerMillion());
            provider.setOutputPricePerMillion(request.outputPerMillion());
            provider.setPriceCurrency(toCurrency(request.currency()));
            providerRepository.save(provider);
        } else {
            AgentCostProperties.Pricing pricing = toPricing(request);
            if (pricing == null) {
                costProperties.getProviders().remove(id);
            } else {
                costProperties.getProviders().put(id, pricing);
            }
        }

        cache.remove(id);
        return describe(id);
    }

    /** Drop a deleted Provider from the in-process lookup cache. */
    public void evict(String providerId) {
        if (providerId != null) {
            cache.remove(providerId);
        }
    }

    private Optional<ResolvedPricing> resolve(String providerId) {
        String id = providerId == null ? null : providerId.trim();
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(id, this::load);
    }

    private Optional<ResolvedPricing> load(String providerId) {
        if (providerRepository != null) {
            Optional<LlmProviderEntity> provider = providerRepository.findById(providerId);
            if (provider.isPresent() && hasDatabaseOverride(provider.get())) {
                LlmProviderEntity entity = provider.get();
                AgentCostProperties.Pricing pricing = new AgentCostProperties.Pricing();
                pricing.setInputPerMillion(entity.getInputPricePerMillion());
                pricing.setOutputPerMillion(entity.getOutputPricePerMillion());
                pricing.setCurrency(toCurrency(entity.getPriceCurrency()));
                return Optional.of(new ResolvedPricing(pricing, "database"));
            }
        }

        AgentCostProperties.Pricing fallback = costProperties.find(providerId);
        return fallback == null
            ? Optional.empty()
            : Optional.of(new ResolvedPricing(fallback, "config"));
    }

    private boolean hasDatabaseOverride(LlmProviderEntity provider) {
        return provider.getInputPricePerMillion() != null
            || provider.getOutputPricePerMillion() != null;
    }

    private ProviderPricingDTO toDto(AgentCostProperties.Pricing pricing, String source) {
        return new ProviderPricingDTO(
            pricing.getInputPerMillion(),
            pricing.getOutputPerMillion(),
            toCurrency(pricing.getCurrency()),
            pricing.configured(),
            source
        );
    }

    private AgentCostProperties.Pricing toPricing(ProviderPricingRequest request) {
        if (request.inputPerMillion() == null && request.outputPerMillion() == null) {
            return null;
        }
        AgentCostProperties.Pricing pricing = new AgentCostProperties.Pricing();
        pricing.setInputPerMillion(request.inputPerMillion());
        pricing.setOutputPerMillion(request.outputPerMillion());
        pricing.setCurrency(toCurrency(request.currency()));
        return pricing;
    }

    private void validate(ProviderPricingRequest request) {
        validatePrice(request.inputPerMillion(), "inputPerMillion");
        validatePrice(request.outputPerMillion(), "outputPerMillion");
        String currency = toCurrency(request.currency());
        if (!currency.matches("[A-Z]{3}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "币种必须是 3 位大写字母，例如 USD 或 CNY");
        }
    }

    private void validatePrice(BigDecimal value, String field) {
        if (value != null && (value.signum() < 0 || value.scale() > 8)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " 必须是非负数，最多 8 位小数");
        }
    }

    private String toCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }
        return currency.trim().toUpperCase();
    }

    private String requireProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider ID 不能为空");
        }
        return providerId.trim();
    }

    private record ResolvedPricing(AgentCostProperties.Pricing pricing, String source) {
    }
}
