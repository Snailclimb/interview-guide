package interview.guide.modules.voiceinterview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VoiceInterviewService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>会话生命周期管理（创建、结束、获取）</li>
 *   <li>阶段转换逻辑</li>
 *   <li>消息持久化和历史记录</li>
 *   <li>Redis 缓存机制</li>
 *   <li>阶段转换判断逻辑</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音面试服务测试")
class VoiceInterviewServiceTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private VoiceInterviewProperties properties;

    @Mock
    private RBucket<VoiceInterviewSessionEntity> bucket;

    @InjectMocks
    private VoiceInterviewService voiceInterviewService;

    private VoiceInterviewProperties defaultProperties;

    @BeforeEach
    void setUp() {
        // Setup default properties
        defaultProperties = new VoiceInterviewProperties();
        VoiceInterviewProperties.PhaseConfig phaseConfig = new VoiceInterviewProperties.PhaseConfig();
        phaseConfig.setIntro(new VoiceInterviewProperties.DurationConfig(3, 5, 8, 2, 5));
        phaseConfig.setTech(new VoiceInterviewProperties.DurationConfig(8, 10, 15, 3, 8));
        phaseConfig.setProject(new VoiceInterviewProperties.DurationConfig(8, 10, 15, 2, 5));
        phaseConfig.setHr(new VoiceInterviewProperties.DurationConfig(3, 5, 8, 2, 5));
        defaultProperties.setPhase(phaseConfig);

        // Setup Redis bucket mock - use lenient to avoid stubbing issues
        lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
        lenient().when(bucket.get()).thenReturn(null);

        // Setup properties mock to return default phase config
        lenient().when(properties.getPhase()).thenReturn(phaseConfig);
    }

    // ==================== 会话创建测试 ====================

    @Nested
    @DisplayName("会话创建测试")
    class CreateSessionTests {

        @Test
        @DisplayName("创建会话 - 验证基本流程")
        void testCreateSession() {
            // Given
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .roleType("ali-p8")
                    .introEnabled(true)
                    .techEnabled(true)
                    .projectEnabled(true)
                    .hrEnabled(true)
                    .plannedDuration(30)
                    .build();

            VoiceInterviewSessionEntity savedSession = VoiceInterviewSessionEntity.builder()
                    .id(1L)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .build();

            when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(savedSession);

            // When
            SessionResponseDTO response = voiceInterviewService.createSession(request);

            // Then
            assertNotNull(response);
            assertEquals(1L, response.getSessionId());
            assertEquals("ali-p8", response.getRoleType());
            assertEquals("INTRO", response.getCurrentPhase());
            assertEquals("IN_PROGRESS", response.getStatus());
            assertNotNull(response.getWebSocketUrl());

            verify(sessionRepository, times(1)).save(any(VoiceInterviewSessionEntity.class));
            verify(bucket, times(1)).set(any(), eq(1L), any());
        }

        @Test
        @DisplayName("创建会话 - 仅启用技术阶段")
        void testCreateSession_TechOnly() {
            // Given
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .roleType("byteance-algo")
                    .introEnabled(false)
                    .techEnabled(true)
                    .projectEnabled(false)
                    .hrEnabled(false)
                    .plannedDuration(15)
                    .build();

            VoiceInterviewSessionEntity savedSession = VoiceInterviewSessionEntity.builder()
                    .id(2L)
                    .roleType("byteance-algo")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .build();

            when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(savedSession);

            // When
            SessionResponseDTO response = voiceInterviewService.createSession(request);

            // Then
            assertEquals("TECH", response.getCurrentPhase());
        }

        @Test
        @DisplayName("创建会话 - 自定义JD文本")
        void testCreateSession_WithCustomJd() {
            // Given
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .roleType("tencent-backend")
                    .customJdText("高级Java工程师，熟悉Spring Cloud")
                    .introEnabled(true)
                    .plannedDuration(25)
                    .build();

            VoiceInterviewSessionEntity savedSession = VoiceInterviewSessionEntity.builder()
                    .id(3L)
                    .roleType("tencent-backend")
                    .customJdText("高级Java工程师，熟悉Spring Cloud")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .build();

            when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(savedSession);

            // When
            ArgumentCaptor<VoiceInterviewSessionEntity> captor =
                    ArgumentCaptor.forClass(VoiceInterviewSessionEntity.class);
            when(sessionRepository.save(captor.capture())).thenReturn(savedSession);

            voiceInterviewService.createSession(request);

            // Then
            VoiceInterviewSessionEntity captured = captor.getValue();
            assertEquals("高级Java工程师，熟悉Spring Cloud", captured.getCustomJdText());
        }
    }

    // ==================== 会话结束测试 ====================

    @Nested
    @DisplayName("会话结束测试")
    class EndSessionTests {

        @Test
        @DisplayName("结束会话 - 验证状态更新和时长计算")
        void testEndSession() {
            // Given
            Long sessionId = 1L;
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(10);
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .startTime(startTime)
                    .plannedDuration(30)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(session);

            // When
            voiceInterviewService.endSession(sessionId.toString());

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED, session.getCurrentPhase());
            assertEquals(VoiceInterviewSessionStatus.COMPLETED, session.getStatus());
            assertNotNull(session.getEndTime());
            assertNotNull(session.getActualDuration());

            // 验证时长约为 10 分钟（允许 1 秒误差）
            int expectedDuration = (int) Duration.between(startTime, LocalDateTime.now()).toSeconds();
            assertTrue(Math.abs(session.getActualDuration() - expectedDuration) <= 1);

            verify(sessionRepository, times(1)).save(session);
            verify(bucket, times(1)).delete();
        }

        @Test
        @DisplayName("结束不存在的会话 - 应静默处理")
        void testEndSession_NotFound() {
            // Given
            Long sessionId = 999L;
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            // When & Then - 不应抛出异常
            assertDoesNotThrow(() -> voiceInterviewService.endSession(sessionId.toString()));

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("结束会话 - 无效ID格式")
        void testEndSession_InvalidId() {
            // When & Then
            assertDoesNotThrow(() -> voiceInterviewService.endSession("invalid"));

            verify(sessionRepository, never()).findById(any());
            verify(sessionRepository, never()).save(any());
        }
    }

    // ==================== 会话恢复测试 ====================

    @Nested
    @DisplayName("会话恢复测试")
    class ResumeSessionTests {

        @Test
        @DisplayName("恢复会话 - 验证历史记录加载")
        void testResumeSession_LoadsConversationHistory() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity pausedSession = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .status(VoiceInterviewSessionStatus.PAUSED)
                    .startTime(LocalDateTime.now().minusMinutes(10))
                    .plannedDuration(30)
                    .build();

            List<VoiceInterviewMessageEntity> history = Arrays.asList(
                    createMessage(sessionId, 1, "用户：你好"),
                    createMessage(sessionId, 2, "AI：你好，请自我介绍")
            );

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(pausedSession));
            when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(pausedSession);
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId)).thenReturn(history);

            // When
            SessionResponseDTO response = voiceInterviewService.resumeSession(sessionId.toString());

            // Then
            assertNotNull(response);
            assertEquals(VoiceInterviewSessionStatus.IN_PROGRESS.name(), response.getStatus());
            assertNotNull(response.getStartTime());
            assertEquals(30, response.getPlannedDuration());

            // Verify conversation history was loaded
            verify(messageRepository, times(1)).findBySessionIdOrderBySequenceNumAsc(sessionId);
            verify(sessionRepository, times(1)).save(argThat(session ->
                    session.getStatus() == VoiceInterviewSessionStatus.IN_PROGRESS &&
                    session.getResumedAt() != null
            ));
        }

        @Test
        @DisplayName("恢复会话 - 状态必须为PAUSED")
        void testResumeSession_InvalidStatus() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity inProgressSession = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(inProgressSession));

            // When & Then
            assertThrows(BusinessException.class, () ->
                    voiceInterviewService.resumeSession(sessionId.toString())
            );

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("恢复会话 - 会话不存在")
        void testResumeSession_SessionNotFound() {
            // Given
            Long sessionId = 999L;
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            // When & Then
            assertThrows(BusinessException.class, () ->
                    voiceInterviewService.resumeSession(sessionId.toString())
            );
        }

        private VoiceInterviewMessageEntity createMessage(Long sessionId, int sequenceNum, String content) {
            VoiceInterviewMessageEntity message = new VoiceInterviewMessageEntity();
            message.setSessionId(sessionId);
            message.setSequenceNum(sequenceNum);
            message.setUserRecognizedText(content);
            return message;
        }
    }

    // ==================== 阶段转换测试 ====================

    @Nested
    @DisplayName("阶段转换测试")
    class PhaseTransitionTests {

        @Test
        @DisplayName("开始新阶段 - INTRO 到 TECH")
        void testStartPhase_IntroToTech() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            // When
            voiceInterviewService.startPhase(sessionId.toString(), "TECH");

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.TECH, session.getCurrentPhase());
            verify(sessionRepository, times(1)).save(session);
            verify(bucket, times(1)).set(any(), eq(1L), any());
        }

        @Test
        @DisplayName("开始新阶段 - 无效阶段名称")
        void testStartPhase_InvalidPhase() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            // When
            voiceInterviewService.startPhase(sessionId.toString(), "INVALID_PHASE");

            // Then - 阶段不应改变
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.INTRO, session.getCurrentPhase());
            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("获取下一阶段 - INTRO -> TECH")
        void testGetNextPhase_IntroToTech() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .techEnabled(true)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.TECH, next);
        }

        @Test
        @DisplayName("获取下一阶段 - TECH -> PROJECT")
        void testGetNextPhase_TechToProject() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .projectEnabled(true)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.PROJECT, next);
        }

        @Test
        @DisplayName("获取下一阶段 - HR -> COMPLETED")
        void testGetNextPhase_HrToCompleted() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.HR)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED, next);
        }

        @Test
        @DisplayName("获取下一阶段 - 跳过未启用的阶段")
        void testGetNextPhase_SkipDisabledPhases() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .techEnabled(false)
                    .projectEnabled(false)
                    .hrEnabled(true)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.HR, next);
        }
    }

    // ==================== 消息持久化测试 ====================

    @Nested
    @DisplayName("消息持久化测试")
    class MessagePersistenceTests {

        @Test
        @DisplayName("保存消息 - 验证基本流程")
        void testSaveMessage() {
            // Given
            Long sessionId = 1L;
            String userText = "我叫张三，有3年Java开发经验";
            String aiText = "你好张三，能详细介绍一下你的项目经验吗？";

            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .build();

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId))
                    .thenReturn(Arrays.asList(
                            VoiceInterviewMessageEntity.builder().sequenceNum(1).build(),
                            VoiceInterviewMessageEntity.builder().sequenceNum(2).build()
                    ));

            // When
            voiceInterviewService.saveMessage(sessionId.toString(), userText, aiText);

            // Then
            ArgumentCaptor<VoiceInterviewMessageEntity> captor =
                    ArgumentCaptor.forClass(VoiceInterviewMessageEntity.class);
            verify(messageRepository, times(1)).save(captor.capture());

            VoiceInterviewMessageEntity savedMessage = captor.getValue();
            assertEquals(sessionId, savedMessage.getSessionId());
            assertEquals("DIALOGUE", savedMessage.getMessageType());
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.INTRO, savedMessage.getPhase());
            assertEquals(userText, savedMessage.getUserRecognizedText());
            assertEquals(aiText, savedMessage.getAiGeneratedText());
            assertEquals(3, savedMessage.getSequenceNum()); // Next sequence after 2 existing messages
        }

        @Test
        @DisplayName("获取对话历史 - 验证排序")
        void testGetConversationHistory() {
            // Given
            Long sessionId = 1L;

            VoiceInterviewMessageEntity msg1 = VoiceInterviewMessageEntity.builder()
                    .id(1L)
                    .sessionId(sessionId)
                    .sequenceNum(1)
                    .userRecognizedText("第一句话")
                    .build();

            VoiceInterviewMessageEntity msg2 = VoiceInterviewMessageEntity.builder()
                    .id(2L)
                    .sessionId(sessionId)
                    .sequenceNum(2)
                    .aiGeneratedText("第一句回复")
                    .build();

            VoiceInterviewMessageEntity msg3 = VoiceInterviewMessageEntity.builder()
                    .id(3L)
                    .sessionId(sessionId)
                    .sequenceNum(3)
                    .userRecognizedText("第二句话")
                    .build();

            List<VoiceInterviewMessageEntity> messages = Arrays.asList(msg1, msg2, msg3); // 正确顺序

            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId))
                    .thenReturn(messages);

            // When
            List<VoiceInterviewMessageEntity> result =
                    voiceInterviewService.getConversationHistory(sessionId.toString());

            // Then
            assertEquals(3, result.size());
            assertEquals(1, result.get(0).getSequenceNum());
            assertEquals(2, result.get(1).getSequenceNum());
            assertEquals(3, result.get(2).getSequenceNum());
        }

        @Test
        @DisplayName("保存消息 - 会话不存在时应静默处理")
        void testSaveMessage_SessionNotFound() {
            // Given
            Long sessionId = 999L;
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

            // When & Then
            assertDoesNotThrow(() ->
                    voiceInterviewService.saveMessage(sessionId.toString(), "user", "ai")
            );

            verify(messageRepository, never()).save(any());
        }
    }

    // ==================== 缓存测试 ====================

    @Nested
    @DisplayName("Redis 缓存测试")
    class CacheTests {

        @Test
        @DisplayName("获取会话 - 缓存命中")
        void testGetSession_CacheHit() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity cachedSession = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .build();

            when(bucket.get()).thenReturn(cachedSession);

            // When
            VoiceInterviewSessionEntity result = voiceInterviewService.getSession(sessionId);

            // Then
            assertNotNull(result);
            assertEquals(sessionId, result.getId());
            assertEquals("ali-p8", result.getRoleType());

            // 验证未查询数据库
            verify(sessionRepository, never()).findById(any());
        }

        @Test
        @DisplayName("获取会话 - 缓存未命中，查询数据库")
        void testGetSession_CacheMiss() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity dbSession = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .roleType("byteance-algo")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.PROJECT)
                    .build();

            when(bucket.get()).thenReturn(null);
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(dbSession));

            // When
            VoiceInterviewSessionEntity result = voiceInterviewService.getSession(sessionId);

            // Then
            assertNotNull(result);
            assertEquals(sessionId, result.getId());
            assertEquals("byteance-algo", result.getRoleType());

            verify(sessionRepository, times(1)).findById(sessionId);
        }

        @Test
        @DisplayName("获取会话 - null ID")
        void testGetSession_NullId() {
            // When
            VoiceInterviewSessionEntity result = voiceInterviewService.getSession((Long) null);

            // Then
            assertNull(result);
            verify(redissonClient, never()).getBucket(anyString());
            verify(sessionRepository, never()).findById(any());
        }

        @Test
        @DisplayName("获取会话DTO - 转换正确")
        void testGetSessionDTO() {
            // Given
            Long sessionId = 1L;
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(sessionId)
                    .roleType("tencent-backend")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.HR)
                    .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                    .startTime(LocalDateTime.now())
                    .plannedDuration(20)
                    .build();

            when(bucket.get()).thenReturn(session);

            // When
            SessionResponseDTO dto = voiceInterviewService.getSessionDTO(sessionId);

            // Then
            assertNotNull(dto);
            assertEquals(sessionId, dto.getSessionId());
            assertEquals("tencent-backend", dto.getRoleType());
            assertEquals("HR", dto.getCurrentPhase());
            assertEquals("IN_PROGRESS", dto.getStatus());
            assertNotNull(dto.getWebSocketUrl());
        }
    }

    // ==================== 阶段转换判断测试 ====================

    @Nested
    @DisplayName("阶段转换判断测试")
    class ShouldTransitionTests {

        @Test
        @DisplayName("判断转换 - 达到最大时长强制转换")
        void testShouldTransitionToNextPhase_MaxDuration() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(16); // 超过 max 15 分钟
            int questionCount = 2; // 少于 min questions

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertTrue(shouldTransition, "达到最大时长应强制转换");
        }

        @Test
        @DisplayName("判断转换 - 达到最大问题数")
        void testShouldTransitionToNextPhase_MaxQuestions() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(5); // 未达 max duration
            int questionCount = 9; // 超过 max 8 个问题

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertTrue(shouldTransition, "达到最大问题数应建议转换");
        }

        @Test
        @DisplayName("判断转换 - 达到建议时长和最小问题数")
        void testShouldTransitionToNextPhase_SuggestedDurationWithMinQuestions() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.PROJECT)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(10); // 达到 suggested 10 分钟
            int questionCount = 3; // 达到 min 2 个问题

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertTrue(shouldTransition, "达到建议时长且达到最小问题数应建议转换");
        }

        @Test
        @DisplayName("判断转换 - 未满足条件不转换")
        void testShouldTransitionToNextPhase_NotMet() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.HR)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(2); // 未达 suggested 5 分钟
            int questionCount = 1; // 未达 min 2 个问题

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertFalse(shouldTransition, "未满足转换条件");
        }

        @Test
        @DisplayName("判断转换 - COMPLETED阶段不应转换")
        void testShouldTransitionToNextPhase_CompletedPhase() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now().minusHours(1);
            int questionCount = 100;

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertFalse(shouldTransition, "COMPLETED 阶段不应转换");
        }

        @Test
        @DisplayName("判断转换 - null 阶段不转换")
        void testShouldTransitionToNextPhase_NullPhase() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(null)
                    .build();

            LocalDateTime phaseStartTime = LocalDateTime.now();
            int questionCount = 10;

            // When
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                    session, phaseStartTime, questionCount
            );

            // Then
            assertFalse(shouldTransition, "null 阶段不应转换");
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("获取当前阶段 - 会话不存在")
        void testGetCurrentPhase_SessionNotFound() {
            // Given
            when(bucket.get()).thenReturn(null);
            when(sessionRepository.findById(anyLong())).thenReturn(Optional.empty());

            // When
            VoiceInterviewSessionEntity.InterviewPhase phase =
                    voiceInterviewService.getCurrentPhase("999");

            // Then
            assertNull(phase);
        }

        @Test
        @DisplayName("解析会话ID - 无效格式")
        void testParseSessionId_InvalidFormat() {
            // Given
            String invalidId = "abc123";

            // When
            VoiceInterviewSessionEntity result = voiceInterviewService.getSession(invalidId);

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("获取下一阶段 - null 当前阶段")
        void testGetNextPhase_NullCurrentPhase() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(null)
                    .introEnabled(true)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.INTRO, next);
        }

        @Test
        @DisplayName("获取下一阶段 - 所有阶段禁用")
        void testGetNextPhase_AllPhasesDisabled() {
            // Given
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .introEnabled(false)
                    .techEnabled(false)
                    .projectEnabled(false)
                    .hrEnabled(false)
                    .build();

            // When
            VoiceInterviewSessionEntity.InterviewPhase next = voiceInterviewService.getNextPhase(session);

            // Then
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED, next);
        }
    }
}
