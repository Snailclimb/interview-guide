# Python 面试重点（语言核心 + 并发 + 工程实践）

## 数据模型与语法特性
- 一切皆对象：`id()`/`type()`/`value`，可变对象（list/dict/set）vs 不可变对象（int/str/tuple）。
- 魔法方法：`__init__` vs `__new__`、`__str__` vs `__repr__`、`__eq__` vs `__hash__`、`__enter__`/`__exit__`（上下文管理器）。
- 多重继承与 MRO：C3 线性化算法、`super()` 沿 MRO 链调用。
- 描述符协议：`__get__`/`__set__`/`__delete__`，property/classmethod/staticmethod 的底层实现。

## 迭代器、生成器与装饰器
- 迭代器协议：`__iter__` + `__next__`，`StopIteration` 终止，`for` 循环底层机制。
- 生成器：`yield` 暂停与恢复、惰性求值、内存优势（处理大数据集/无限序列）。
- 生成器表达式 vs 列表推导式：内存 vs 速度的取舍。
- 装饰器：闭包 + `@` 语法糖、带参数装饰器（三层嵌套）、`functools.wraps` 保留元信息。
- 类装饰器 vs 函数装饰器，`__call__` 实现。

## GIL 与并发模型
- GIL（全局解释器锁）：CPython 的内存安全机制，CPU 密集型多线程无法并行。
- 多线程（`threading`）：适合 I/O 密集型（网络请求/文件读写），GIL 在 I/O 等待时释放。
- 多进程（`multiprocessing`）：绕过 GIL，进程间通信（Queue/Pipe/共享内存），进程池。
- 异步（`asyncio`）：事件循环 + 协程（`async`/`await`），适合高并发 I/O，单线程无锁。
- `concurrent.futures`：`ThreadPoolExecutor` vs `ProcessPoolExecutor`，统一的 Future 接口。

## 内存管理与垃圾回收
- 引用计数：主要回收机制，实时性好但无法处理循环引用。
- 分代 GC：0/1/2 三代，新对象从 0 代开始，存活时间越长晋升概率越低。
- 循环引用检测：`gc` 模块的标记-清除算法，`gc.collect()` 手动触发。
- 内存优化：`__slots__` 禁止动态属性字典、`sys.getsizeof`、内存分析工具（`tracemalloc`/`memory_profiler`）。

## 类型系统与工程实践
- Type Hints：`typing` 模块（`Optional`/`Union`/`Generic`/`Protocol`），mypy 静态检查。
- dataclass vs attrs vs Pydantic：数据类定义、校验、序列化的选型。
- 包管理：`pip` + `requirements.txt` vs `poetry`/`uv`（锁文件、依赖解析）。
- 虚拟环境：`venv`/`conda`，隔离项目依赖避免版本冲突。
- 代码质量：`ruff`（lint + format）、`pytest`（测试框架）、`pre-commit`（Git 钩子）。

## 面试追问模板
- Python 的 GIL 在你的项目中造成过问题吗？怎么解决的？
- 装饰器写过什么功能？带参数的装饰器怎么实现的？
- 内存泄漏怎么排查？用过哪些工具？
- 大量数据处理时，你会用生成器还是列表？权衡点是什么？
