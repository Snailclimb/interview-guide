package interview.guide.common.annotation;

import interview.guide.common.aspect.RateLimitAspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解
 * 用于方法级别的限流控制，支持可重复注解实现多维度独立限流
 * <p>
 * 每个注解实例代表一条独立的限流规则，拥有独立的 count/interval/timeUnit 配置。
 * 同一方法上可标注多个 @RateLimit，所有规则必须全部通过才允许请求。
 * <p>
 * 示例：
 * <pre>
 * &#64;RateLimit(dimension = Dimension.GLOBAL, count = 100)
 * &#64;RateLimit(dimension = Dimension.IP, count = 5)
 * public Result query() { ... }
 * </pre>
 *
 * @see RateLimitAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.Container.class)
public @interface RateLimit {

    /**
     * 限流维度枚举
     */
    enum Dimension {
        /**
         * 全局限流：对所有请求统一限流
         */
        GLOBAL,
        /**
         * IP限流：按客户端IP地址限流
         */
        IP,
        /**
         * 用户限流：按用户ID限流
         */
        USER
    }

    /**
     * 限流维度
     * 每个注解实例对应一个维度，多条规则通过可重复注解实现
     *
     * @return 限流维度
     */
    Dimension dimension() default Dimension.GLOBAL;

    /**
     * 在指定时间窗口内允许的最大请求数
     * 例如：count = 10, interval = 1, timeUnit = MINUTES 表示每分钟最多 10 次
     *
     * @return 令牌总数
     */
    double count();

    /**
     * 时间窗口大小
     * 默认 1
     *
     * @return 时间窗口
     */
    long interval() default 1;

    /**
     * 时间单位
     * 默认为秒，即默认"每秒 count 次"
     *
     * @return 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 等待令牌的超时时间
     * 如果设置为0，表示不等待，直接获取令牌，失败则拒绝
     * 如果大于0，会尝试等待指定时间获取令牌
     *
     * @return 超时时间
     */
    long timeout() default 0;

    /**
     * 降级方法名
     * 当限流触发时，调用指定方法进行降级处理
     * 降级方法支持：
     * 1. 无参方法
     * 2. 与原方法参数列表完全一致的方法
     * 降级方法必须在同一个类中，返回值类型与原方法兼容
     * 如果为空字符串，则抛出 RateLimitExceededException 异常
     *
     * @return 降级方法名
     */
    String fallback() default "";

    /**
     * 时间单位枚举
     */
    enum TimeUnit {
        MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS
    }

    /**
     * 可重复注解容器
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Container {
        RateLimit[] value();
    }
}
