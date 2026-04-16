# MySQL 面试重点

## 索引
- B+ 树为什么适合磁盘索引（有序、范围查询、叶子链表）。
- 覆盖索引与回表成本，联合索引最左前缀原则与索引下推。
- 索引失效场景：函数转换、隐式类型转换、OR、LIKE 前缀通配、非最左列。
- EXPLAIN 执行计划：type/key/Extra 字段含义，Extra 中 Using filesort/Using temporary。

## 事务与 MVCC
- ACID 含义，事务隔离级别（RU/RC/RR/SERIALIZABLE）与各自解决的问题。
- MySQL 默认 RR，InnoDB 通过 MVCC + Next-Key Lock 解决幻读。
- MVCC 原理：隐藏列（trx_id/roll_pointer）、Undo Log 版本链、ReadView。
- 当前读 vs 快照读，RR 下当前读仍加间隙锁。

## 锁机制
- 表级锁 vs 行级锁，InnoDB 行锁（Record/Gap/Next-Key）。
- 意向锁的作用（快速判断表级冲突），IS/IX 与 S/X 的兼容矩阵。
- 死锁检测与避免：按固定顺序加锁、缩短事务、降低隔离级别。

## 存储引擎与日志
- InnoDB vs MyISAM：事务、行锁、外键、崩溃恢复。
- Redo Log（WAL、crash-safe）vs Undo Log（MVCC、回滚）vs Binlog（主从复制、归档）。
- 两阶段提交保证 Redo Log 与 Binlog 一致性。

## 性能优化
- 慢 SQL 定位：slow_query_log、pt-query-digest。
- 分库分表策略：垂直拆分 vs 水平拆分，ShardingSphere 中间件。
- 深度分页优化：游标分页、延迟关联、子查询先查主键。
- 数据冷热分离，读写分离架构。

## 面试追问模板
- 这条 SQL 走了什么索引？能否优化？
- 高并发写入时，锁冲突怎么解决？
- 主从延迟怎么处理？数据一致性如何保证？
