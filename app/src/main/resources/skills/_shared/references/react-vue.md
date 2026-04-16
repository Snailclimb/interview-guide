# React/Vue 面试重点（框架核心 + 工程化）

## React 核心机制
- Fiber 架构：链表结构替代递归、可中断渲染、时间切片（Time Slicing）与优先级调度。
- Virtual DOM：Diff 算法（同层比较、key 的作用）、Reconciliation 过程。
- Hooks 规则：调用顺序一致（不能在条件/循环中调用）、闭包陷阱（stale closure）与解决方案。
- 常用 Hooks：`useState`/`useEffect`（依赖数组）/`useCallback`/`useMemo`/`useRef`/`useContext`。
- `useEffect` vs `useLayoutEffect`：异步 vs 同步、绘制前 vs 绘制后。
- React 18 并发特性：`useTransition`（非紧急更新降级）、`useDeferredValue`（延迟重渲染）、Suspense 数据获取。

## Vue 核心机制
- 响应式原理：Vue 2（`Object.defineProperty` 拦截 getter/setter）vs Vue 3（`Proxy` 代理全对象）。
- Composition API：`ref`/`reactive`/`computed`/`watch`/`watchEffect`，逻辑组合 vs Options API 逻辑分散。
- 模板编译：模板→AST→渲染函数→Virtual DOM，静态提升与补丁标记（Patch Flag）。
- 组件更新粒度：Vue 3 基于依赖追踪的精确更新 vs React 整体重渲染（需 memo/shouldComponentUpdate）。
- Vue 3 编译优化：Block Tree、静态提升（hoistStatic）、缓存事件处理器。

## 状态管理
- React：Context API（轻量）、Redux（单向数据流/reducer/middleware）、Zustand（极简 Hook 式）、Jotai/Recoil（原子化）。
- Vue：Pinia（Vue 3 官方推荐，TS 友好、模块化）、Vuex（Vue 2 时代，mutation/action 分离）。
- 状态分层：组件本地状态 vs 全局共享状态 vs 服务端状态（React Query/TanStack Query）。

## 组件设计
- 组件通信：props/emit、provide/inject（跨层级）、事件总线、状态管理。
- 组件抽象：高阶组件（HOC）vs Render Props vs Hooks（React）、插槽（Slots）vs 作用域插槽（Vue）。
- 受控 vs 非受控组件：表单管理策略、`ref` 获取 DOM/组件实例。
- 性能优化：`React.memo`/`useMemo`/`useCallback`、Vue `v-once`/`v-memo`、虚拟列表（react-window/vue-virtual-scroller）。

## 路由与 SSR
- React Router：声明式路由、嵌套路由、路由守卫、懒加载（`React.lazy` + `Suspense`）。
- Vue Router：动态路由匹配、导航守卫（beforeEach/afterEach）、路由懒加载。
- SSR vs CSR vs SSG：SEO、首屏速度、服务器成本权衡。
- Next.js：SSR/SSG/ISR/App Router、Nuxt.js：服务端渲染与混合渲染。
- Hydration：SSR HTML + 客户端 JS 绑定事件，注水不匹配（hydration mismatch）排查。

## 工程化
- 构建工具：Vite（ESM 开发服务器 + Rollup 构建）vs Webpack（loader/plugin 生态）。
- 代码规范：ESLint + Prettier、Husky + lint-staged（提交前检查）。
- 测试：Jest/Vitest（单元测试）、React Testing Library/Vue Test Utils（组件测试）、Playwright/Cypress（E2E）。
- 微前端：Module Federation、qiankun、single-spa，应用隔离与通信。

## 面试追问模板
- React 的 Fiber 架构解决了什么问题？调度是怎么实现的？
- Vue 3 的 Proxy 响应式和 Vue 2 的 defineProperty 有什么本质区别？
- 大列表渲染卡顿怎么优化？虚拟列表的原理是什么？
- 你的项目状态管理怎么选型的？遇到过什么问题？
