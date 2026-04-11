package interview.guide.common.aspect;

import interview.guide.common.annotation.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流注解单元测试
 */
@DisplayName("限流注解单元测试")
class RateLimitScriptTest {

    @Test
    @DisplayName("验证注解元配置")
    void testAnnotationMetadata() {
        assertEquals(RetentionPolicy.RUNTIME,
                RateLimit.class.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[]{ElementType.METHOD},
                RateLimit.class.getAnnotation(Target.class).value());
        assertEquals(3, RateLimit.Dimension.values().length);
        assertEquals(5, RateLimit.TimeUnit.values().length);
        // 验证 @Repeatable 存在
        assertNotNull(RateLimit.class.getAnnotation(Repeatable.class));
    }

    @Test
    @DisplayName("验证注解默认值")
    void testDefaultValues() throws NoSuchMethodException {
        RateLimit ann = TestClass.class.getMethod("defaultMethod").getAnnotation(RateLimit.class);

        assertEquals(RateLimit.Dimension.GLOBAL, ann.dimension());
        assertEquals(0, ann.timeout());
        assertEquals(1, ann.interval());
        assertEquals(RateLimit.TimeUnit.SECONDS, ann.timeUnit());
        assertEquals("", ann.fallback());
    }

    @Test
    @DisplayName("验证自定义值")
    void testCustomValues() throws NoSuchMethodException {
        RateLimit ann = TestClass.class.getMethod("customMethod").getAnnotation(RateLimit.class);

        assertEquals(RateLimit.Dimension.IP, ann.dimension());
        assertEquals(100.0, ann.count(), 0.001);
        assertEquals(2, ann.timeout());
        assertEquals(2, ann.interval());
        assertEquals(RateLimit.TimeUnit.SECONDS, ann.timeUnit());
        assertEquals("fallback", ann.fallback());
    }

    @Test
    @DisplayName("验证可重复注解：多条规则独立获取")
    void testRepeatableAnnotations() throws NoSuchMethodException {
        Method method = TestClass.class.getMethod("multiRuleMethod");
        RateLimit[] anns = method.getAnnotationsByType(RateLimit.class);

        assertEquals(2, anns.length);
        assertEquals(RateLimit.Dimension.GLOBAL, anns[0].dimension());
        assertEquals(100.0, anns[0].count(), 0.001);
        assertEquals(RateLimit.Dimension.IP, anns[1].dimension());
        assertEquals(50.0, anns[1].count(), 0.001);
    }

    @SuppressWarnings("unused")
    static class TestClass {
        @RateLimit(count = 10)
        public void defaultMethod() {}

        @RateLimit(dimension = RateLimit.Dimension.IP,
                count = 100, interval = 2, timeout = 2,
                timeUnit = RateLimit.TimeUnit.SECONDS, fallback = "fallback")
        public void customMethod() {}

        @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 100)
        @RateLimit(dimension = RateLimit.Dimension.IP, count = 50)
        public void multiRuleMethod() {}
    }
}
