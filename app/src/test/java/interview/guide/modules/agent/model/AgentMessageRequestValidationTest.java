package interview.guide.modules.agent.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 请求传输边界校验")
class AgentMessageRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void tearDown() {
        validator = null;
    }

    @Test
    @DisplayName("历史消息数量不能超过 32 条")
    void rejectsTooManyHistoryMessages() {
        List<AgentHistoryMessage> history = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            history.add(new AgentHistoryMessage("user", "问题"));
        }

        AgentMessageRequest request = new AgentMessageRequest("review", "请复盘", history, null);

        assertThat(validator.validate(request))
            .extracting(ConstraintViolation::getMessage)
            .contains("历史消息最多保留 32 条");
    }

    @Test
    @DisplayName("单条历史消息不能超过 32000 个字符")
    void rejectsOversizedHistoryMessage() {
        AgentMessageRequest request = new AgentMessageRequest(
            "review", "请复盘", List.of(new AgentHistoryMessage("assistant", "x".repeat(32_001))), null
        );

        assertThat(validator.validate(request))
            .extracting(ConstraintViolation::getMessage)
            .contains("历史消息不能超过 32000 个字符");
    }
}
