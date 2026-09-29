package interview.guide.modules.knowledgebase.service;

import interview.guide.common.config.KnowledgeBaseUploadProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.file.KnowledgeBaseUploadLimiter;
import interview.guide.modules.knowledgebase.KnowledgeBaseController;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库上传并发保护")
class KnowledgeBaseUploadConcurrencyTest {

  @Mock
  private KnowledgeBaseParseService parseService;
  @Mock
  private KnowledgeBasePersistenceService persistenceService;
  @Mock
  private FileStorageService storageService;
  @Mock
  private KnowledgeBaseRepository repository;
  @Mock
  private FileValidationService validationService;
  @Mock
  private FileHashService hashService;
  @Mock
  private VectorizeStreamProducer producer;

  private KnowledgeBaseUploadService service;
  private MultipartFile file;

  @BeforeEach
  void setUp() {
    service = createService(4);
    file = new MockMultipartFile("file", "guide.pdf", "application/pdf", new byte[] {1, 2, 3});
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("四个上传处理中时拒绝第五个请求，完成后允许再次上传")
  void shouldRejectFifthInFlightUploadAndAllowRetryAfterCompletion(boolean virtualThreads)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger validationCalls = new AtomicInteger();
    doAnswer(invocation -> {
      if (validationCalls.incrementAndGet() <= 4) {
        entered.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      }
      return null;
    }).when(validationService).validateFile(any(), anyLong(), eq("知识库"));
    stubDuplicateUpload();
    ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), virtualThreads
            ? Thread.ofVirtual().name("upload-regression-", 0).factory()
            : Thread.ofPlatform().name("upload-regression-", 0).factory());
    List<Future<Map<String, Object>>> uploads = new ArrayList<>();

    try {
      for (int index = 0; index < 4; index++) {
        uploads.add(executor.submit(() -> service.uploadKnowledgeBase(file, null, null)));
      }
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> service.uploadKnowledgeBase(file, null, null))
          .isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getCode());
            assertThat(exception.getMessage()).contains("上传繁忙", "重试");
          });
      assertThatThrownBy(() -> service.uploadKnowledgeBase(file, null, null))
          .isInstanceOf(BusinessException.class);

      assertThat(validationCalls).hasValue(4);
      release.countDown();
      for (Future<Map<String, Object>> upload : uploads) {
        assertThat(upload.get(5, TimeUnit.SECONDS)).containsEntry("duplicate", true);
      }
      assertThat(service.uploadKnowledgeBase(file, null, null)).containsEntry("duplicate", true);
      verify(storageService, never()).uploadKnowledgeBase(any());
      verify(producer, never()).sendVectorizeTask(anyLong());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("校验异常不会泄漏上传名额，原始业务异常保持不变")
  void shouldReleasePermitAfterValidationFailure() {
    service = createService(1);
    BusinessException failure = new BusinessException(ErrorCode.BAD_REQUEST, "文件校验失败");
    doThrow(failure).doNothing().when(validationService)
        .validateFile(any(), anyLong(), eq("知识库"));
    stubDuplicateUpload();

    assertThatThrownBy(() -> service.uploadKnowledgeBase(file, null, null)).isSameAs(failure);

    assertThat(service.uploadKnowledgeBase(file, null, null)).containsEntry("duplicate", true);
  }

  @Test
  @DisplayName("重复上传处理异常也会释放名额，并保留原始异常")
  void shouldReleasePermitAfterDuplicateHandlingFailure() {
    service = createService(1);
    KnowledgeBaseEntity existing = stubDuplicateUpload();
    BusinessException failure = new BusinessException(ErrorCode.INTERNAL_ERROR, "数据库读取失败");
    when(persistenceService.handleDuplicateKnowledgeBase(existing, "existing-hash"))
        .thenThrow(failure).thenReturn(Map.of("duplicate", true));

    assertThatThrownBy(() -> service.uploadKnowledgeBase(file, null, null)).isSameAs(failure);

    assertThat(service.uploadKnowledgeBase(file, null, null)).containsEntry("duplicate", true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("存储或数据库保存失败后释放名额，保留原异常和孤儿对象补偿")
  void shouldReleasePermitAfterStorageOrDatabaseFailure(boolean storageFails) {
    service = createService(1);
    KnowledgeBaseEntity existing = stubDuplicateUpload();
    when(repository.findByFileHash("existing-hash"))
        .thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
    BusinessException failure = new BusinessException(
        storageFails ? ErrorCode.STORAGE_UPLOAD_FAILED : ErrorCode.INTERNAL_ERROR, "上传依赖失败");
    if (storageFails) {
      when(storageService.uploadKnowledgeBase(file)).thenThrow(failure);
    } else {
      when(storageService.uploadKnowledgeBase(file)).thenReturn("kb/orphan");
      when(storageService.getFileUrl("kb/orphan")).thenReturn("http://storage/kb/orphan");
      when(persistenceService.saveKnowledgeBase(any(), any(), any(), anyString(), anyString(),
          anyString())).thenThrow(failure);
    }

    assertThatThrownBy(() -> service.uploadKnowledgeBase(file, null, null)).isSameAs(failure);

    assertThat(service.uploadKnowledgeBase(file, null, null)).containsEntry("duplicate", true);
    if (!storageFails) {
      verify(storageService).deleteKnowledgeBase("kb/orphan");
    }
    verify(producer, never()).sendVectorizeTask(anyLong());
  }

  @Test
  @DisplayName("上传调用因中断退出后恢复名额，不影响后续上传")
  void shouldReleasePermitWhenInterruptedUploadExits() throws Exception {
    service = createService(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicInteger validationCalls = new AtomicInteger();
    doAnswer(invocation -> {
      if (validationCalls.incrementAndGet() == 1) {
        entered.countDown();
        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
      }
      return null;
    }).when(validationService).validateFile(any(), anyLong(), eq("知识库"));
    stubDuplicateUpload();
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1));
    try {
      Future<?> upload = executor.submit(() -> {
        try {
          service.uploadKnowledgeBase(file, null, null);
        } finally {
          exited.countDown();
        }
      });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(upload.cancel(true)).isTrue();
      assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(service.uploadKnowledgeBase(file, null, null)).containsEntry("duplicate", true);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("超限上传沿用 HTTP 200 与 Result 错误码，并返回可重试提示")
  void shouldReturnBusyResultFromUploadEndpoint() throws Exception {
    service = createService(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger validationCalls = new AtomicInteger();
    doAnswer(invocation -> {
      if (validationCalls.incrementAndGet() == 1) {
        entered.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      }
      return null;
    }).when(validationService).validateFile(any(), anyLong(), eq("知识库"));
    stubDuplicateUpload();
    MockMvc api = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service,
            mock(KnowledgeBaseQueryService.class), mock(KnowledgeBaseListService.class),
            mock(KnowledgeBaseDeleteService.class)))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1));
    try {
      Future<?> upload = executor.submit(() -> service.uploadKnowledgeBase(file, null, null));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      api.perform(multipart("/api/knowledgebase/upload").file((MockMultipartFile) file))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(8001))
          .andExpect(jsonPath("$.message").value("知识库上传繁忙，请稍后重试"));

      assertThat(validationCalls).hasValue(1);
      release.countDown();
      upload.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private KnowledgeBaseUploadService createService(int maximum) {
    KnowledgeBaseUploadProperties properties = new KnowledgeBaseUploadProperties();
    properties.setMaxConcurrent(maximum);
    return new KnowledgeBaseUploadService(parseService, persistenceService, storageService,
        repository, validationService, hashService, producer,
        new KnowledgeBaseUploadLimiter(properties));
  }

  private KnowledgeBaseEntity stubDuplicateUpload() {
    KnowledgeBaseEntity existing = new KnowledgeBaseEntity();
    existing.setId(42L);
    when(parseService.detectContentType(file)).thenReturn("application/pdf");
    when(hashService.calculateHash(file)).thenReturn("existing-hash");
    when(repository.findByFileHash("existing-hash")).thenReturn(Optional.of(existing));
    when(persistenceService.handleDuplicateKnowledgeBase(existing, "existing-hash"))
        .thenReturn(Map.of("duplicate", true));
    return existing;
  }
}
