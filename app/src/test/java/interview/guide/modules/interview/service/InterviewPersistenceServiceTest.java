package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("知识库面试保存时写入 interviewCategory")
  void shouldSaveInterviewCategoryForKnowledgeBaseSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid1", null, 1, List.of(), "dashscope",
        "knowledge-base", "mid", "KNOWLEDGE_BASE", 9L, "MySQL");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isEqualTo("MySQL");
    assertThat(saved.getKnowledgeBaseId()).isEqualTo(9L);
    assertThat(saved.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
  }

  @Test
  @DisplayName("普通面试保存时 interviewCategory 保持 null")
  void shouldKeepInterviewCategoryNullForNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", null, 1, List.of(), "dashscope", "java-backend", "mid");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isNull();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getKnowledgeBaseId()).isNull();
  }

  @Test
  @DisplayName("Agent 访问未归属的登录前记录时只认领一次")
  void shouldClaimUnownedLegacySessionForAgentOwner() {
    InterviewPersistenceService service = newService();
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("legacy-session");
    when(sessionRepository.findBySessionIdForUpdate("legacy-session"))
        .thenReturn(java.util.Optional.of(session));
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.findBySessionIdForOwnerOrClaimLegacy("legacy-session", "user-1");

    assertThat(result).containsSame(session);
    assertThat(session.getOwnerId()).isEqualTo("user-1");
    verify(sessionRepository).save(session);
  }

  @Test
  @DisplayName("Agent 不会认领已经属于其他账号的记录")
  void shouldNotClaimOwnedSessionForAnotherAgentOwner() {
    InterviewPersistenceService service = newService();
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("owned-session");
    session.setOwnerId("user-1");
    when(sessionRepository.findBySessionIdForUpdate("owned-session"))
        .thenReturn(java.util.Optional.of(session));

    var result = service.findBySessionIdForOwnerOrClaimLegacy("owned-session", "user-2");

    assertThat(result).isEmpty();
    verify(sessionRepository, never()).save(any(InterviewSessionEntity.class));
  }

  private InterviewPersistenceService newService() {
    return new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        resumeRepository,
        objectMapper
    );
  }
}
