# 将当前可回答问题保存在 Agent Run

在 #4 中，`AgentRun` 在 `WAITING_USER` 时保存当前可回答问题的不透明身份，Answer Message 只能与该身份匹配。选择这一最小 Run 级指针，而不复用 LEGACY 的 `currentQuestionIndex` 或从尚未实现的模型/用户可见 Step 推导：前者会耦合并改变 LEGACY 题单流程，后者会提前实现 #6 的出题事实；#6 将成为发布题目事实并更新该指针的唯一生产者。

该指针仅在 `WAITING_USER` 有效：数据库约束要求该状态时非空、其他状态时为空；所有离开等待态的
状态迁移必须清空它。这让持久化不变量与 #4 的 Answer Message 校验保持一致，同时不把题目生产逻辑
提前带入本 ticket。
