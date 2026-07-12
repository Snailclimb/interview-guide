package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.DiscoverModelsRequest;
import interview.guide.modules.llmprovider.dto.DiscoverModelsResponse;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {

  private final LlmProviderConfigService configService;

  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<ProviderDTO>> listProviders() {
    return Result.success(configService.listProviders());
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<ProviderDTO> getProvider(@PathVariable String id) {
    return Result.success(configService.getProvider(id));
  }

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
    configService.createProvider(request);
    return Result.success();
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateProvider(@PathVariable String id,
      @RequestBody UpdateProviderRequest request) {
    configService.updateProvider(id, request);
    return Result.success();
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> deleteProvider(@PathVariable String id) {
    configService.deleteProvider(id);
    return Result.success();
  }

  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testProvider(@PathVariable String id) {
    return Result.success(configService.testProvider(id));
  }

  @PostMapping("/models/discover")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<DiscoverModelsResponse> discoverModels(
      @RequestBody @Valid DiscoverModelsRequest request) {
    return Result.success(configService.discoverModels(request));
  }

  @PostMapping("/reload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> reloadProviders() {
    configService.reloadProviders();
    return Result.success();
  }

  @GetMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<DefaultProviderDTO> getDefaultProvider() {
    return Result.success(configService.getDefaultProvider());
  }

  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultProvider(request);
    return Result.success();
  }

  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultEmbeddingProvider(request);
    return Result.success();
  }

  // ===== Voice ASR/TTS Config =====

  @GetMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<AsrConfigDTO> getAsrConfig() {
    return Result.success(configService.getAsrConfig());
  }

  @PutMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
    configService.updateAsrConfig(request);
    return Result.success();
  }

  @GetMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<TtsConfigDTO> getTtsConfig() {
    return Result.success(configService.getTtsConfig());
  }

  @PutMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
    configService.updateTtsConfig(request);
    return Result.success();
  }

  @PostMapping("/voice/asr/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testAsrConfig() {
    return Result.success(configService.testAsrConfig());
  }
}
