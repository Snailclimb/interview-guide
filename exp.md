# Agent 化改造经验沉淀

> 本文件记录可跨 ticket 复用的工程经验。某个 ticket 的时间线、需求和验收证据保留在
> [`progress.md`](progress.md)；只有经过实施或审查证实、值得复用的结论才写入这里。

## EXP-0001：把 Agent 持久化 Schema 的所有权交给版本化迁移

- 来源：GitHub Issue #2（G0）。
- 结论：测试和开发环境中 Hibernate 自动建表会掩盖迁移缺失；Agent 表必须由 Flyway migration
  创建，生产 profile 使用 schema validate 而不是 update。
- 应用：新增 Agent 表或约束时，先写版本化 migration，再在 H2 中验证全新启动与重复 migration；
  不把“本地 Hibernate 能启动”当作生产迁移证据。

## EXP-0002：幂等与唯一性必须同时由数据库和服务层保证

- 来源：GitHub Issue #2 / G1。
- 结论：服务层先查后写无法消除并发窗口；对逻辑幂等键建立数据库唯一约束，并在唯一约束竞争后
  重读胜出记录、核对请求指纹。
- 应用：后续 Answer Message、Tool Call 和 Approval 的重复投递同样要先定义稳定标识、持久化唯一
  约束和竞争失败后的可预期响应。

## EXP-0003：持久化事件应从不可变 Step 派生

- 来源：GitHub Issue #3（G2 基础）。
- 结论：生命周期变更以 `(runId, stepSequence)` 唯一的不可变 Step 作为事实源；事件查询只是该事实
  的脱敏视图，按 `stepSequence` 补读，不在第一阶段新增独立事件表、Outbox 或 Redis 队列。
- 应用：新增模型、工具、错误和 Checkpoint 事件时，先定义 Step 事实与序号/幂等关系，再定义前端
  事件 DTO；不得持久化隐藏思维链、密钥、未授权资源或完整原始工具结果。

## EXP-0004：提交存在不等于 ticket 完成

- 来源：2026-07-14 对 #3 的审查。
- 结论：代码已在 HEAD 并不能证明 ticket 可关闭。必须同时核对远端 Issue、需求/约束、聚焦与持久化
  测试、完整回归（按变更风险）和 Standards/Spec 双轴审查；完成后立即回写 `progress.md` 与远端状态。
- 应用：每个新 ticket 在开工前先创建 progress 条目，关闭前补齐验收命令及结果、踩坑和对应 EXP 编号；
  避免文档、分支和 Issue 三者漂移。
