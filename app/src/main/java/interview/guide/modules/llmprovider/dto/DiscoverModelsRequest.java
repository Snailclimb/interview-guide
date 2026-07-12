package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

public record DiscoverModelsRequest(
    @NotBlank String baseUrl,
    @NotBlank String apiKey
) {
}
