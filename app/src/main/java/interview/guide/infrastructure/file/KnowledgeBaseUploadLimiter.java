package interview.guide.infrastructure.file;

import interview.guide.common.config.KnowledgeBaseUploadProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 限制一个应用实例中正在执行的知识库上传，不排队等待。
 *
 * <p>不替代请求频率限制，也不限制 multipart 接收或后台向量化任务。
 */
@Component
public class KnowledgeBaseUploadLimiter {

  private final Semaphore permits;

  public KnowledgeBaseUploadLimiter(KnowledgeBaseUploadProperties properties) {
    Assert.isTrue(properties.getMaxConcurrent() > 0, "知识库上传并发上限必须大于 0");
    permits = new Semaphore(properties.getMaxConcurrent());
  }

  public <T> T execute(Supplier<T> upload) {
    if (!permits.tryAcquire()) {
      throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "知识库上传繁忙，请稍后重试");
    }
    try {
      return upload.get();
    } finally {
      permits.release();
    }
  }
}
