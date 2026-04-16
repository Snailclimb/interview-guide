# Codex 项目规范速查（基于 CLAUDE.md）

> 用于代码评审与实现时的快速自检，优先级从高到低。

## 1. 必须遵守

- 分层：`Controller -> Service -> Repository`，Controller 不写业务逻辑。
- 业务异常统一使用 `BusinessException(ErrorCode.XXX, message)`。
- 禁止 `throw new RuntimeException(...)`。
- 配置优先 `@ConfigurationProperties`，避免在 Service 中散落 `@Value`。
- 禁止内联全限定类名（如 `org.springframework...`），统一 import。
- 禁止 `Executors.newXxxThreadPool()`，改用可控的 `ThreadPoolExecutor`。
- 事务方法内禁止调用外部 API（LLM/S3 等）。
- 日志异常必须带堆栈：`log.error("...", e)`。

## 2. AI 与异步

- ChatClient 统一经 `LlmProviderRegistry.getChatClientOrDefault(provider)` 获取。
- 结构化输出统一走 `StructuredOutputInvoker`。
- 异步任务统一用 Redis Stream 模板（`AbstractStreamProducer/Consumer`）。
- 消费任务重试上限 3 次，失败要可观测、可追踪。

## 3. 命名与格式

- Bean 后缀语义：`Entity / DTO / Request / Response`。
- 不直接返回 Entity 给前端。
- 禁止通配符导入。
- Java 代码 2 空格缩进、单行尽量不超过 100 列。

## 4. 提交前检查清单

- 是否引入了 `RuntimeException` / `Executors.new*` / Service 中 `@Value`。
- 是否有事务边界错误（事务内外部调用、同类事务自调用）。
- 是否有可复现的回归（编译、关键链路手测）。
- 是否有明确日志与错误码，便于排查线上问题。
