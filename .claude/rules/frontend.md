---
paths:
  - "frontend/src/**/*.ts"
  - "frontend/src/**/*.tsx"
  - "frontend/src/**/*.css"
  - "frontend/package.json"
  - "frontend/vite.config.ts"
---

# Frontend Rules

## Structure

- 页面组件放在 `frontend/src/pages/`。
- 可复用组件放在 `frontend/src/components/`。
- API 客户端放在 `frontend/src/api/`，复用 `request.ts`。
- 共享类型放在 `frontend/src/types/`。
- 路由路径放在 `frontend/src/constants/routes.ts`。

## API

- 后端接口返回 `Result<T>` 结构，前端 API 层负责解包和错误处理。
- 不要在页面组件里直接拼接重复的 Axios 配置。
- Provider、知识库、面试、简历等模块沿用现有 API 文件拆分。

## UI

- 延续现有 React + TailwindCSS 4 风格，不引入新的 UI 框架。
- 操作按钮优先使用 `lucide-react` 图标加清晰文本或 tooltip。
- 表单状态要包含 loading、success、error 和 disabled 处理。
- 不要把业务说明性长文塞进页面；优先让界面直接可操作。

## Verification

- 前端改动后运行 `cd frontend && pnpm run build`。
- 涉及浏览器交互时，用本地浏览器实际打开页面验证。
