---
paths:
  - "app/src/main/java/**/*.java"
  - "app/src/test/java/**/*.java"
  - "app/src/main/resources/**/*.yml"
---

# Backend Rules

## Layers

- Controller 只做路由、参数校验、限流注解和 Service 委托。
- Service 负责编排业务流程，事务注解只放 Service 层。
- Repository 只做数据访问，继承 `JpaRepository`。
- Infrastructure 能力放在 `common/` 或 `infrastructure/`，不要塞进业务模块。

## Naming

- JPA 持久化对象使用 `XxxEntity`。
- 跨层数据传输使用 `XxxDTO`。
- 前端请求体使用 `XxxRequest`，前端响应体使用 `XxxResponse`。
- 不可变请求/响应优先使用 `record`。

## Exceptions

- 业务失败使用 `BusinessException(ErrorCode.XXX, "描述信息")`。
- 保留业务异常原样抛出：`catch (BusinessException e) { throw e; }`。
- 禁止用 `RuntimeException` 表达业务错误。
- 禁止吞异常；记录异常时把异常对象作为日志最后一个参数。

## Transactions

- `@Transactional` 只放 Service 层公开方法。
- 事务内不得调用 LLM、S3、外部 HTTP 或耗时文件解析。
- 避免同类内部调用 `@Transactional` 方法，必要时拆分到独立 Service。
- 读多写少的查询使用 `@Transactional(readOnly = true)`。

## Mapping

- Entity 不直接返回给前端。
- Entity 到 DTO/Response 的重复转换优先使用 MapStruct。
- 简单一次性字段复制可以使用 `BeanUtils.copyProperties`。

## Style

- Java 代码使用 2 空格缩进，列宽尽量控制在 100 字符。
- 禁止通配符导入，避免内联全限定类名。
- 优先使用构造器注入和 `@RequiredArgsConstructor`。
- 现代 Java 特性可用：switch 表达式、pattern matching、text blocks。
