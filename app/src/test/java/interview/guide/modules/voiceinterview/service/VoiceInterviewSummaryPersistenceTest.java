package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试摘要持久化")
class VoiceInterviewSummaryPersistenceTest {

  @Mock
  private VoiceInterviewSessionRepository sessionRepository;
  @Mock
  private VoiceInterviewMessageRepository messageRepository;
  @Mock
  private VoiceInterviewEvaluationRepository evaluationRepository;
  @Mock
  private RedissonClient redissonClient;
  @Mock
  private VoiceInterviewProperties properties;
  @Mock
  private VoiceEvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private ResumeRepository resumeRepository;

  private VoiceInterviewService service;

  @BeforeEach
  void setUp() {
    service = new VoiceInterviewService(
        sessionRepository,
        messageRepository,
        evaluationRepository,
        redissonClient,
        properties,
        evaluateStreamProducer,
        llmProviderRegistry,
        resumeRepository
    );
  }

  @Test
  @DisplayName("公开对话历史不应包含内部 SUMMARY 行")
  void conversationHistoryExcludesSummaryRows() {
    VoiceInterviewMessageEntity dialogue = VoiceInterviewMessageEntity.builder()
        .id(2L)
        .sessionId(42L)
        .messageType("DIALOGUE")
        .aiGeneratedText("请介绍一下项目")
        .sequenceNum(1)
        .build();
    when(messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
        42L, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY))
        .thenReturn(List.of(dialogue));

    List<VoiceInterviewMessageEntity> result = service.getConversationHistory("42");

    assertThat(result).containsExactly(dialogue);
  }

  @Test
  @DisplayName("更新摘要应复用已有行，避免先删后插的竞态窗口")
  void saveSummaryUpdatesExistingRow() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder().id(42L).build();
    VoiceInterviewMessageEntity existing = VoiceInterviewMessageEntity.builder()
        .id(7L)
        .sessionId(42L)
        .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
        .aiGeneratedText("旧摘要")
        .sequenceNum(-6)
        .build();
    when(sessionRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(session));
    when(messageRepository.findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
        42L, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY))
        .thenReturn(Optional.of(existing));

    existing.setSummaryCoveredSequenceNum(6);
    service.saveSummaryRow("42", "新摘要", 10);

    assertThat(existing.getAiGeneratedText()).isEqualTo("新摘要");
    // 真实边界写入新字段；负 sequenceNum 仅保留排序语义不再编码轮数
    assertThat(existing.getSummaryCoveredSequenceNum()).isEqualTo(10);
    assertThat(existing.getSequenceNum()).isEqualTo(-6);
    verify(messageRepository).save(existing);
    verify(messageRepository, never()).deleteBySessionId(42L);
  }
}
