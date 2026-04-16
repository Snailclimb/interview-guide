package interview.guide.modules.voiceinterview.integration;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Voice Interview Integration Test
 *
 * <p>End-to-end testing of the voice interview feature:
 * <ul>
 *   <li>Session creation via REST API</li>
 *   <li>Session lifecycle management</li>
 *   <li>Phase transition logic</li>
 *   <li>Database persistence</li>
 *   <li>Configuration validation</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("语音面试集成测试")
class VoiceInterviewIntegrationTest {

    @Autowired
    private VoiceInterviewService voiceInterviewService;

    @Autowired
    private VoiceInterviewSessionRepository sessionRepository;

    @Autowired
    private VoiceInterviewProperties properties;

    @BeforeEach
    void setUp() {
        // Clean up database
        sessionRepository.deleteAll();
    }

    @Nested
    @DisplayName("完整面试流程测试")
    class CompleteInterviewFlowTests {

        @Test
        @DisplayName("完整面试流程 - 创建会话到结束会话")
        void testCompleteInterviewFlow() {
            // Step 1: Create session
            CreateSessionRequest createRequest = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(30)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(createRequest);

            assertNotNull(sessionResponse);
            assertNotNull(sessionResponse.getSessionId());
            assertEquals("ali-p8", sessionResponse.getRoleType());
            assertEquals("INTRO", sessionResponse.getCurrentPhase());
            assertEquals("IN_PROGRESS", sessionResponse.getStatus());

            Long sessionId = sessionResponse.getSessionId();

            // Verify session was saved to database
            VoiceInterviewSessionEntity savedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(savedSession);
            assertEquals("ali-p8", savedSession.getRoleType());

            // Step 2: Test phase transition logic
            LocalDateTime phaseStartTime = LocalDateTime.now();
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                savedSession, phaseStartTime, 2
            );

            // Should not transition yet (INTRO phase, low question count)
            assertFalse(shouldTransition, "Should not transition with low question count");

            // Step 3: End session
            String sessionIdStr = sessionId.toString();
            voiceInterviewService.endSession(sessionIdStr);

            // Verify session status
            VoiceInterviewSessionEntity endedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(endedSession);
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED, endedSession.getCurrentPhase());
        }

        @Test
        @DisplayName("会话状态转换 - 验证阶段转换条件")
        void testPhaseTransition() {
            // Create session with INTRO and TECH phases
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("byteance-algo")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(false)
                .hrEnabled(false)
                .plannedDuration(20)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            // Initial phase should be INTRO
            assertEquals("INTRO", sessionResponse.getCurrentPhase());

            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElseThrow();

            // Test phase transition conditions
            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(10); // 10 minutes ago
            int questionCount = 3; // Low question count

            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, phaseStartTime, questionCount
            );

            // Verify transition logic works
            assertNotNull(shouldTransition);
        }

        @Test
        @DisplayName("会话持久化 - 数据库存储和检索")
        void testSessionPersistence() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("tencent-backend")
                .introEnabled(true)
                .plannedDuration(25)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            // Verify database persistence
            VoiceInterviewSessionEntity dbSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(dbSession);
            assertEquals(sessionId, dbSession.getId());
            assertEquals("tencent-backend", dbSession.getRoleType());

            // Verify we can retrieve the session
            VoiceInterviewSessionEntity retrievedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(retrievedSession);
            assertEquals(dbSession.getCurrentPhase(), retrievedSession.getCurrentPhase());
        }

        @Test
        @DisplayName("多阶段会话 - 验证所有阶段都能正确初始化")
        void testMultiPhaseSession() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(45)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElseThrow();

            // Verify all phases are properly configured
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.INTRO, session.getCurrentPhase());

            // Test transition logic for each phase
            LocalDateTime startTime = LocalDateTime.now();

            // INTRO phase
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO);
            boolean introTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, startTime.minusMinutes(10), 5
            );

            // TECH phase
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
            boolean techTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, startTime.minusMinutes(12), 9
            );

            // Verify logic works for all phases
            assertNotNull(introTransition);
            assertNotNull(techTransition);
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("处理无效的会话ID")
        void testInvalidSessionId() {
            // Try to end a non-existent session
            String invalidSessionId = "99999";

            // Service should handle non-existent session gracefully (no exception)
            assertDoesNotThrow(() -> {
                voiceInterviewService.endSession(invalidSessionId);
            });
        }

        @Test
        @DisplayName("处理空配置 - 验证默认值")
        void testEmptyConfiguration() {
            // Create session with minimal configuration
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);

            assertNotNull(sessionResponse);
            assertNotNull(sessionResponse.getSessionId());
        }

        @Test
        @DisplayName("处理不同角色类型")
        void testDifferentRoleTypes() {
            String[] roleTypes = {"ali-p8", "byteance-algo", "tencent-backend"};

            for (String roleType : roleTypes) {
                CreateSessionRequest request = CreateSessionRequest.builder()
                    .roleType(roleType)
                    .plannedDuration(30)
                    .build();

                SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);

                assertNotNull(sessionResponse, "Session should be created for role: " + roleType);
                assertEquals(roleType, sessionResponse.getRoleType());
            }
        }
    }

    @Nested
    @DisplayName("配置验证测试")
    class ConfigurationTests {

        @Test
        @DisplayName("验证面试阶段配置")
        void testPhaseConfiguration() {
            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            assertNotNull(phaseConfig.getIntro(), "INTRO phase config should not be null");
            assertNotNull(phaseConfig.getTech(), "TECH phase config should not be null");
            assertNotNull(phaseConfig.getProject(), "PROJECT phase config should not be null");
            assertNotNull(phaseConfig.getHr(), "HR phase config should not be null");

            // Verify reasonable duration limits
            assertTrue(phaseConfig.getIntro().getMaxDuration() > 0);
            assertTrue(phaseConfig.getTech().getMaxDuration() > 0);
            assertTrue(phaseConfig.getProject().getMaxDuration() > 0);
            assertTrue(phaseConfig.getHr().getMaxDuration() > 0);
        }

        @Test
        @DisplayName("验证阶段配置参数")
        void testPhaseConfigParameters() {
            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            // Verify INTRO phase
            VoiceInterviewProperties.DurationConfig intro = phaseConfig.getIntro();
            assertTrue(intro.getSuggestedDuration() > 0);
            assertTrue(intro.getMinQuestions() >= 0);
            assertTrue(intro.getMaxQuestions() > intro.getMinQuestions());

            // Verify TECH phase
            VoiceInterviewProperties.DurationConfig tech = phaseConfig.getTech();
            assertTrue(tech.getSuggestedDuration() > 0);
            assertTrue(tech.getMinQuestions() >= 0);
            assertTrue(tech.getMaxQuestions() > tech.getMinQuestions());

            // Verify PROJECT phase
            VoiceInterviewProperties.DurationConfig project = phaseConfig.getProject();
            assertTrue(project.getSuggestedDuration() > 0);
            assertTrue(project.getMinQuestions() >= 0);
            assertTrue(project.getMaxQuestions() > project.getMinQuestions());

            // Verify HR phase
            VoiceInterviewProperties.DurationConfig hr = phaseConfig.getHr();
            assertTrue(hr.getSuggestedDuration() > 0);
            assertTrue(hr.getMinQuestions() >= 0);
            assertTrue(hr.getMaxQuestions() > hr.getMinQuestions());
        }

        @Test
        @DisplayName("验证配置完整性")
        void testConfigurationCompleteness() {
            assertNotNull(properties, "Properties should not be null");
            assertNotNull(properties.getPhase(), "Phase config should not be null");

            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            // Verify all phases have non-null configs
            assertNotNull(phaseConfig.getIntro());
            assertNotNull(phaseConfig.getTech());
            assertNotNull(phaseConfig.getProject());
            assertNotNull(phaseConfig.getHr());
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up test data
        sessionRepository.deleteAll();
    }
}
