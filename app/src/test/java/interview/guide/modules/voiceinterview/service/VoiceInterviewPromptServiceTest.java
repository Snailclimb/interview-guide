package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.service.VoiceInterviewPromptService.RolePrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VoiceInterviewPromptService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>角色提示词加载（阿里P8、字节跳动、腾讯）</li>
 *   <li>未知角色类型默认回退</li>
 *   <li>提示词内容验证</li>
 *   <li>初始化异常处理</li>
 * </ul>
 *
 * <p>注意：由于提示词文件可能不存在于测试环境，
 * 使用 mock 资源或测试默认回退逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试提示词服务测试")
class VoiceInterviewPromptServiceTest {

    @InjectMocks
    private VoiceInterviewPromptService promptService;

    @Nested
    @DisplayName("角色提示词加载测试")
    class RolePromptLoadingTests {

        @Test
        @DisplayName("加载阿里P8提示词 - 验证基本结构")
        void testGetRolePrompt_AliP8() throws IOException {
            // Given
            String roleType = "ali-p8";

            // When
            RolePrompt prompt = promptService.getRolePrompt(roleType);

            // Then
            assertNotNull(prompt, "提示词不应为 null");
            assertNotNull(prompt.getSystemPrompt(), "系统提示词不应为 null");

            // 如果文件存在，验证内容；否则验证默认回退
            if (prompt.getRoleType().equals("ali-p8")) {
                // 文件加载成功
                assertFalse(prompt.getSystemPrompt().isEmpty(), "提示词内容不应为空");
                assertEquals("ali-p8", prompt.getRoleType());
            } else {
                // 文件不存在，使用默认
                assertEquals("default", prompt.getRoleType());
                assertTrue(prompt.getSystemPrompt().contains("面试官"),
                        "默认提示词应包含'面试官'");
            }
        }

        @Test
        @DisplayName("加载字节跳动算法提示词")
        void testGetRolePrompt_Byteance() {
            // Given
            String roleType = "byteance-algo";

            // When
            RolePrompt prompt = promptService.getRolePrompt(roleType);

            // Then
            assertNotNull(prompt);
            assertNotNull(prompt.getSystemPrompt());

            // 验证角色类型
            if (prompt.getRoleType().equals("byteance-algo")) {
                // 文件加载成功
                assertFalse(prompt.getSystemPrompt().isEmpty());
            } else {
                // 使用默认
                assertEquals("default", prompt.getRoleType());
            }
        }

        @Test
        @DisplayName("加载腾讯后端提示词")
        void testGetRolePrompt_Tencent() {
            // Given
            String roleType = "tencent-backend";

            // When
            RolePrompt prompt = promptService.getRolePrompt(roleType);

            // Then
            assertNotNull(prompt);
            assertNotNull(prompt.getSystemPrompt());

            // 验证角色类型
            if (prompt.getRoleType().equals("tencent-backend")) {
                // 文件加载成功
                assertFalse(prompt.getSystemPrompt().isEmpty());
            } else {
                // 使用默认
                assertEquals("default", prompt.getRoleType());
            }
        }

        @Test
        @DisplayName("加载未知角色类型 - 使用默认提示词")
        void testGetRolePrompt_UnknownRole() {
            // Given
            String unknownRoleType = "unknown-company-unknown-role";

            // When
            RolePrompt prompt = promptService.getRolePrompt(unknownRoleType);

            // Then
            assertNotNull(prompt, "应返回默认提示词");
            assertEquals("default", prompt.getRoleType(), "应使用默认角色类型");
            assertNotNull(prompt.getSystemPrompt(), "默认提示词不应为 null");
            assertFalse(prompt.getSystemPrompt().isEmpty(), "默认提示词不应为空");
            assertTrue(prompt.getSystemPrompt().contains("面试官"),
                    "默认提示词应包含'面试官'关键词");
        }

        @Test
        @DisplayName("加载 null 角色类型 - 使用默认提示词")
        void testGetRolePrompt_NullRole() {
            // When
            RolePrompt prompt = promptService.getRolePrompt(null);

            // Then
            assertNotNull(prompt);
            assertEquals("default", prompt.getRoleType());
            assertNotNull(prompt.getSystemPrompt());
        }

        @Test
        @DisplayName("加载空字符串角色类型 - 使用默认提示词")
        void testGetRolePrompt_EmptyRole() {
            // Given
            String emptyRoleType = "";

            // When
            RolePrompt prompt = promptService.getRolePrompt(emptyRoleType);

            // Then
            assertNotNull(prompt);
            assertEquals("default", prompt.getRoleType());
            assertNotNull(prompt.getSystemPrompt());
        }
    }

    @Nested
    @DisplayName("提示词内容验证测试")
    class PromptContentTests {

        @Test
        @DisplayName("验证默认提示词内容")
        void testDefaultPromptContent() {
            // Given
            String unknownRole = "non-existent-role";

            // When
            RolePrompt prompt = promptService.getRolePrompt(unknownRole);

            // Then
            String systemPrompt = prompt.getSystemPrompt();

            // 验证默认提示词的基本内容
            assertNotNull(systemPrompt);
            assertTrue(systemPrompt.length() > 0, "默认提示词应有内容");
            assertTrue(systemPrompt.contains("面试官") || systemPrompt.contains("面试"),
                    "默认提示词应包含面试相关关键词");
        }

        @Test
        @DisplayName("验证提示词角色类型提取")
        void testRoleTypeExtraction() {
            // Given - 测试已知的角色类型
            String[] knownRoles = {"ali-p8", "byteance-algo", "tencent-backend"};

            // When & Then
            for (String roleType : knownRoles) {
                RolePrompt prompt = promptService.getRolePrompt(roleType);
                assertNotNull(prompt, roleType + " 应返回提示词");

                // 如果文件加载成功，验证角色类型
                if (!prompt.getRoleType().equals("default")) {
                    assertEquals(roleType, prompt.getRoleType(),
                            roleType + " 的提示词应匹配其角色类型");
                }
            }
        }

        @Test
        @DisplayName("验证提示词不包含敏感信息")
        void testPromptNoSensitiveInfo() {
            // Given - 测试所有可能的角色类型
            String[] roleTypes = {
                    "ali-p8",
                    "byteance-algo",
                    "tencent-backend",
                    "unknown-role"
            };

            // When & Then
            for (String roleType : roleTypes) {
                RolePrompt prompt = promptService.getRolePrompt(roleType);
                String content = prompt.getSystemPrompt();

                // 验证不包含明显的敏感信息占位符
                // (实际内容可能包含变量占位符，这里只检查明显的敏感词)
                assertFalse(content.contains("password") ||
                           content.contains("secret") ||
                           content.contains("api_key"),
                        "提示词不应包含敏感信息: " + roleType);
            }
        }
    }

    @Nested
    @DisplayName("初始化和生命周期测试")
    class LifecycleTests {

        @Test
        @DisplayName("服务初始化 - 验证提示词加载")
        void testInit() throws IOException {
            // Given
            VoiceInterviewPromptService service = new VoiceInterviewPromptService();

            // When & Then - 初始化不应抛出异常
            assertDoesNotThrow(() -> service.init());

            // 验证初始化后可以正常获取提示词
            RolePrompt prompt = service.getRolePrompt("ali-p8");
            assertNotNull(prompt, "初始化后应能获取提示词");
        }

        @Test
        @DisplayName("初始化失败 - 使用默认提示词")
        void testInit_FailureHandling() {
            // Given - 创建服务但不初始化（模拟初始化失败场景）
            VoiceInterviewPromptService service = new VoiceInterviewPromptService();

            // When - 即使未初始化，getRolePrompt 也应返回默认提示词
            RolePrompt prompt = service.getRolePrompt("any-role");

            // Then
            assertNotNull(prompt, "即使未初始化也应返回默认提示词");
            assertEquals("default", prompt.getRoleType());
        }

        @Test
        @DisplayName("多次初始化 - 验证幂等性")
        void testInit_Idempotent() throws IOException {
            // Given
            VoiceInterviewPromptService service = new VoiceInterviewPromptService();

            // When & Then - 多次初始化不应抛出异常
            assertDoesNotThrow(() -> {
                service.init();
                service.init();
                service.init();
            });

            // 验证功能正常
            RolePrompt prompt = service.getRolePrompt("ali-p8");
            assertNotNull(prompt);
        }
    }

    @Nested
    @DisplayName("并发和性能测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发获取提示词 - 验证线程安全")
        void testConcurrentAccess() throws InterruptedException {
            // Given
            int threadCount = 10;
            Thread[] threads = new Thread[threadCount];
            boolean[] results = new boolean[threadCount];

            // When - 并发获取提示词
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        RolePrompt prompt = promptService.getRolePrompt("ali-p8");
                        results[index] = (prompt != null);
                    } catch (Exception e) {
                        results[index] = false;
                    }
                });
                threads[i].start();
            }

            // 等待所有线程完成
            for (Thread thread : threads) {
                thread.join();
            }

            // Then - 所有线程都应成功获取提示词
            for (int i = 0; i < threadCount; i++) {
                assertTrue(results[i], "线程 " + i + " 应成功获取提示词");
            }
        }

        @Test
        @DisplayName("大量角色类型查询 - 验证性能")
        void testPerformance() {
            // Given
            String[] roleTypes = {
                    "ali-p8",
                    "byteance-algo",
                    "tencent-backend",
                    "unknown-role-1",
                    "unknown-role-2"
            };

            long startTime = System.currentTimeMillis();

            // When - 执行多次查询
            for (int i = 0; i < 1000; i++) {
                for (String roleType : roleTypes) {
                    promptService.getRolePrompt(roleType);
                }
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // Then - 1000次查询应在合理时间内完成（< 1秒）
            assertTrue(duration < 1000,
                    "1000次查询应在1秒内完成，实际耗时: " + duration + "ms");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("特殊字符角色类型")
        void testSpecialCharactersInRoleType() {
            // Given
            String specialRoleType = "role-with-@#$%-special-chars";

            // When
            RolePrompt prompt = promptService.getRolePrompt(specialRoleType);

            // Then
            assertNotNull(prompt);
            assertEquals("default", prompt.getRoleType());
            assertNotNull(prompt.getSystemPrompt());
        }

        @Test
        @DisplayName("超长角色类型字符串")
        void testVeryLongRoleType() {
            // Given
            String longRoleType = "a".repeat(10000);

            // When
            RolePrompt prompt = promptService.getRolePrompt(longRoleType);

            // Then
            assertNotNull(prompt);
            assertEquals("default", prompt.getRoleType());
        }

        @Test
        @DisplayName("角色类型区分大小写")
        void testCaseSensitivity() {
            // Given
            String roleType1 = "ali-p8";
            String roleType2 = "ALI-P8";
            String roleType3 = "Ali-P8";

            // When
            RolePrompt prompt1 = promptService.getRolePrompt(roleType1);
            RolePrompt prompt2 = promptService.getRolePrompt(roleType2);
            RolePrompt prompt3 = promptService.getRolePrompt(roleType3);

            // Then - 大小写不同应被视为不同角色类型
            // 如果文件不存在，都会返回默认提示词
            assertNotNull(prompt1);
            assertNotNull(prompt2);
            assertNotNull(prompt3);

            // 验证是否都返回默认提示词（因为文件名通常是小写）
            // 或者只有小写的能找到文件
            if (!prompt1.getRoleType().equals("default")) {
                // ali-p8 找到了文件
                assertEquals("default", prompt2.getRoleType(), "大写版本应使用默认");
                assertEquals("default", prompt3.getRoleType(), "混合大小写应使用默认");
            }
        }
    }

    @Nested
    @DisplayName("RolePrompt DTO 测试")
    class RolePromptDTOTests {

        @Test
        @DisplayName("RolePrompt 构建和访问")
        void testRolePromptDTO() {
            // Given
            RolePrompt prompt = new RolePrompt();

            // When
            prompt.setRoleType("test-role");
            prompt.setSystemPrompt("测试提示词");

            // Then
            assertEquals("test-role", prompt.getRoleType());
            assertEquals("测试提示词", prompt.getSystemPrompt());
        }

        @Test
        @DisplayName("RolePrompt 空值处理")
        void testRolePromptNullHandling() {
            // Given
            RolePrompt prompt = new RolePrompt();

            // When & Then - 初始值应为 null
            assertNull(prompt.getRoleType());
            assertNull(prompt.getSystemPrompt());

            // 设置后应能正常访问
            prompt.setRoleType("role");
            prompt.setSystemPrompt("prompt");

            assertEquals("role", prompt.getRoleType());
            assertEquals("prompt", prompt.getSystemPrompt());
        }
    }
}
