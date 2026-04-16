# JavaScript 面试重点（基础 + 异步 + 工程化）

## 类型与作用域
- 8 种数据类型（7 原始 + Object），`typeof null === "object"` 历史遗留。
- `let`/`const` vs `var`：块级作用域、暂时性死区（TDZ）、变量提升差异。
- 作用域链：词法作用域（静态），引擎沿作用域链逐层查找标识符。
- 闭包：函数与其词法环境的绑定，经典场景（防抖/节流/私有变量/柯里化）与内存泄漏风险。

## 原型与继承
- 原型链：`__proto__` vs `prototype`，属性查找沿原型链向上。
- `Object.create()` vs `new`：直接指定原型 vs 构造函数实例化。
- `class` 语法糖：`extends`/`super` 底层仍基于原型链，`static` 方法不可继承到实例。
- `Object.setPrototypeOf` 性能问题，推荐 `Object.create` 替代。

## 异步编程
- 事件循环：宏任务（setTimeout/setInterval/I/O）与微任务（Promise.then/MutationObserver）执行顺序。
- Promise：状态机（pending→fulfilled/rejected），链式调用与错误冒泡，`Promise.all` vs `Promise.allSettled` vs `Promise.race`。
- `async/await`：语法糖，异常用 `try/catch` 捕获，并行场景仍需 `Promise.all`。
- `async` 函数中的并发陷阱：串行 `await` vs 并行 `Promise.all`，性能差异。

## ES6+ 核心特性
- 解构赋值（数组/对象/默认值）、展开/剩余运算符。
- 箭头函数：没有 `this`/`arguments`/`prototype`，不适合作为构造函数。
- `Symbol`：唯一标识符，`Symbol.iterator` 与 `for...of` 协议。
- `Map`/`Set`/`WeakMap`/`WeakSet`：与 Object/Array 的适用场景对比。
- `Proxy`/`Reflect`：元编程基础，Vue 3 响应式原理的核心。

## DOM 与 BOM
- DOM 操作成本：重排（Layout）与重绘（Paint），批量更新策略（`DocumentFragment`/虚拟 DOM）。
- 事件模型：捕获→目标→冒泡，`addEventListener` 第三个参数，事件委托。
- `requestAnimationFrame` vs `setTimeout`：与屏幕刷新率同步，避免丢帧。

## 模块化与工程化
- CommonJS（`require`/`module.exports`，同步加载）vs ES Modules（`import`/`export`，静态分析）。
- Tree-shaking 原理：ES Modules 静态结构使编译期可判定副作用。
- `package.json` 依赖管理：`dependencies` vs `devDependencies`，语义化版本（semver）。
- TypeScript 类型系统：接口/泛型/联合类型/类型守卫，与运行时无关。

## 面试追问模板
- 闭包在实际项目中用在哪里？遇到过内存泄漏吗？
- 宏任务和微任务的执行顺序，你能举个复杂场景说明吗？
- Promise 链中某个环节没 return，会发生什么？
- CommonJS 和 ES Modules 能混用吗？打包工具怎么处理？
