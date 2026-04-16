# 设计模式面试重点

## 创建型
- 单例模式：饿汉/懒汉/DCL/静态内部类/枚举，DCL 为何要 volatile，枚举防反射/序列化。
- 工厂模式：简单工厂→工厂方法→抽象工厂，开闭原则的递进体现。
- 建造者模式：与工厂模式的区别，链式调用场景（Lombok @Builder）。

## 结构型
- 代理模式：JDK 动态代理 vs CGLIB，Spring AOP 默认选型策略。
- 适配器模式：类适配器 vs 对象适配器，Slf4J 日志门面的适配原理。
- 装饰器模式：与代理模式的区别，Java I/O 流的装饰链。

## 行为型
- 观察者模式：JDK Observable（已废弃）vs 事件驱动，Spring Event 机制。
- 策略模式：消除 if-else，结合工厂或 Spring 注入实现策略路由。
- 责任链模式：Servlet Filter / Spring Interceptor / Netty Pipeline。
- 模板方法模式：Spring AbstractApplicationContext#refresh。
- 状态模式：与策略模式的区别（状态内部转换 vs 外部切换）。

## 设计原则
- SOLID 原则在代码中的体现，重点考察开闭原则和依赖倒置。
- 合成/聚合复用 vs 继承复用。

## 框架中的设计模式
- Spring：单例（Bean 默认）、工厂（BeanFactory）、代理（AOP）、观察者（Event）、模板方法（JdbcTemplate）。
- JDK：迭代器（Collection）、装饰器（I/O）、适配器（Arrays.asList）。
- MyBatis：代理（MapperProxy）、模板方法（BaseExecutor）。

## 面试追问模板
- 你的项目中哪里用到了设计模式？为什么选这个模式？
- 如果不用这个模式，代码会变成什么样？
- 这个模式有什么缺点？什么场景下不适合？
