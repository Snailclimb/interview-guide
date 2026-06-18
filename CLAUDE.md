@AGENTS.md

# Claude Code Instructions

- `AGENTS.md` 是本仓库的共享规则源；更新长期规则时优先改 `AGENTS.md`，不要在这里复制一份。
- 本文件只保留 Claude Code 专属入口和加载提示，避免根目录上下文膨胀。
- 目录细则在 `.claude/rules/`，处理匹配文件前先读取对应规则。
- 个人偏好和临时调试结论放 `CLAUDE.local.md` 或 Claude Memory，不要提交到仓库。

## Path Rules

- Backend: `.claude/rules/backend.md`
- AI / async / rate limit: `.claude/rules/ai-and-async.md`
- Frontend: `.claude/rules/frontend.md`

## Maintenance

- 新增规则前先判断：删掉这条后，Claude 是否更容易犯同类错误。
- 能被测试、格式化、Hook 或 CI 强制的规则，不要只写成自然语言。
- 如果同一条规则反复被忽略，优先精简规则文件，而不是继续加粗或加感叹号。
