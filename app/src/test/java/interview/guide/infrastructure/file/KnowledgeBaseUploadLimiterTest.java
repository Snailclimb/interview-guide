package interview.guide.infrastructure.file;

import interview.guide.common.config.KnowledgeBaseUploadProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("上传名额释放边界")
class KnowledgeBaseUploadLimiterTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("业务异常与 Error 原样抛出，之后仍可再次执行上传")
  void shouldReleasePermitAndPreserveFailure(boolean error) {
    KnowledgeBaseUploadProperties properties = new KnowledgeBaseUploadProperties();
    properties.setMaxConcurrent(1);
    KnowledgeBaseUploadLimiter limiter = new KnowledgeBaseUploadLimiter(properties);
    BusinessException businessFailure = new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED);
    AssertionError unexpectedFailure = new AssertionError("unexpected failure");

    assertThatThrownBy(() -> limiter.execute(() -> {
      if (error) {
        throw unexpectedFailure;
      }
      throw businessFailure;
    })).isSameAs(error ? unexpectedFailure : businessFailure);

    assertThat(limiter.execute(() -> "uploaded")).isEqualTo("uploaded");
  }
}
