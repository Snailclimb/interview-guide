# Linux Codex 开发交接

更新日期：2026-07-15

这份文档交接给在 Linux 服务器上继续开发的 Codex。以本文件所在提交为基线，不要从未提交的 Windows 工作区复制文件。

## 获取基线

```bash
git clone https://github.com/Belfast-byte/interview-guide.git
cd interview-guide
git switch --track -c codex/siliconflow-and-architecture-docs belfast/codex/siliconflow-and-architecture-docs
git log --oneline -n 12
```

仓库还配置了指向 `Snailclimb/interview-guide` 的 `origin`。本项目的 Agent ticket、分支发布和 Issue
核对均使用 `Belfast-byte/interview-guide`；执行 GitHub CLI 命令时显式写 `-R Belfast-byte/interview-guide`。

## 先阅读

1. `AGENTS.md`：项目架构、代码规则和验证命令。
2. `goal.md`：Agent 化目标、阶段路线和验收出口。
3. `CONTEXT.md`：领域词汇，特别是 Step 是事实源、Checkpoint 是可重建快照。
4. `docs/agent-runtime-technical-spec.md` 与 ADR-0002、ADR-0006、ADR-0010。
5. `progress.md`：每个 ticket 的需求、约束、验收、Red/Green 证据、审查和踩坑。
6. `exp.md`：跨 ticket 可复用的经验。

## 已完成并已验证

- #2：Flyway 管理 Agent Schema，生产环境使用 validate；Agent 默认开关关闭。
- #3：暂停/取消的不可变 Agent Step 与按序事件查询。
- #4：Answer Message 幂等、当前问题校验、`WAITING_USER -> RUNNING` 条件推进。
- #5 的基础切片：V4 迁移增加 Step 的当前问题身份、每 Run 一个 Checkpoint、Checkpoint 游标与 Step
  的复合外键；状态 Step 落盘后替换 v1 JSON Checkpoint。

当前 #5 基础切片的聚焦验证已经通过：

```bash
./gradlew :app:test --no-daemon --tests "interview.guide.modules.agentinterview.AgentRunApiContractTest" --tests "interview.guide.infrastructure.agent.persistence.AgentRunRepositoryTest"
```

完整后端回归也已于 2026-07-15 通过：

```bash
./gradlew :app:test --no-daemon --rerun-tasks
```

结果为 5 个任务实际执行、2 分 13 秒 `BUILD SUCCESSFUL`；仅有既有的 Voice 过时 API 与 Agent API
未检查操作编译警告。

## 当前 frontier：继续 GitHub Issue #5

#5 尚未完成。下一位 Codex 应按下列顺序继续，并在每个切片开始前和完成后更新 `progress.md`：

1. 将 `AgentCheckpointState` 改为可可靠反序列化的 record 或显式 `@JsonCreator` 模型。
2. 为 `AgentStepRepository` 加入完整升序读取；验证序列从 1 开始连续，且前后状态能衔接。
3. 实现恢复协调器：快照 JSON、版本、游标或状态不一致时，从完整连续 Step 重建并替换 Checkpoint。
4. 账本缺失、断裂、未知 Step 或状态无法解释时，将 Run 保持/转为 `PAUSED`，返回专用业务错误；
   不写伪造 Checkpoint，也不重放结果。
5. 实现 `POST /api/agent/runs/{runId}/resume`：仅连续账本上的 `PAUSED` Run 可恢复；使用条件更新确保
   仅一个并发请求能推进 `PAUSED -> RUNNING`，并写新的状态 Step 与 Checkpoint。
6. 用 `ApplicationRunner` 在启动时将遗留 `RUNNING` 安全转为 `PAUSED`；无论功能开关是否关闭都处理。
7. 运行完整后端回归，再进行 Standards / Spec 双轴审查；验收完成后更新 `exp.md` 与 Issue 状态。

#5 不得提前实现或调用 LLM、工具、S3、外部 HTTP、Agent 队列或前端 Workspace；真实 ReAct Turn 属于 #6。

## 工作方式与边界

- 每个开始的 ticket 都必须先在 `progress.md` 记录需求、约束、验收条件、Agent 分工和测试缝；实现过程的
  Red/Green 命令与结果、踩坑和审查结论也必须补齐。
- 只有已验证、可跨 ticket 复用的结论写入 `exp.md`。
- Controller 只路由/校验/委托；业务编排在 Service，事务保持短小；不能在事务中调用 LLM、S3 或 HTTP。
- 不要修改 Legacy 面试、语音、资源资料等无关范围。不要把 Checkpoint 当成第二事实源，也不要保存隐藏推理。
- 完成 #5 前，不得启动依赖它的 #6。远端 #3/#4 是否关闭须在发布和验收记录齐备后再处理。

## 最终验收命令

```bash
./gradlew :app:test --no-daemon --rerun-tasks
```

如工作区有用户未提交改动，先用 `git status --short` 划清范围，只显式暂存本 ticket 的文件。
