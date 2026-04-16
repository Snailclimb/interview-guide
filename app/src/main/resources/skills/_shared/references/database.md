# 数据库通用面试重点（关系型 + NoSQL 选型）

## 事务与隔离级别
- ACID 含义：原子性（Undo Log）、一致性（约束与规则）、隔离性（锁/MVCC）、持久性（Redo Log）。
- 四种隔离级别：读未提交→读已提交→可重复读→串行化，各自解决的脏读/不可重复读/幻读问题。
- MVCC 原理：版本链（隐藏列 trx_id + roll_pointer）+ ReadView 快照，RC/RR 下 ReadView 生成时机差异。
- 长事务危害：锁持有时间长、Undo Log 膨胀、MVCC 快照无法回收。

## 索引
- B+ 树适合磁盘索引：有序、范围查询、叶子链表、扇出大树高低。
- 覆盖索引避免回表，联合索引最左前缀原则，索引下推（ICP）减少回表次数。
- 索引失效：函数转换、隐式类型转换、OR 条件、LIKE 前缀通配、非最左列。
- 唯一索引 vs 普通索引：查询性能差异（Change Buffer）、写入场景选择。

## SQL 优化
- EXPLAIN 执行计划：type（ref/range/index/all）、key、rows、Extra（Using index/Using filesort/Using temporary）。
- 慢 SQL 定位：`slow_query_log` + `pt-query-digest`，重点关注全表扫描与大结果集。
- 深度分页优化：游标分页（WHERE id > last_id）、延迟关联（子查询先查主键）。
- 批量操作优化：批量 INSERT 替代逐条、INSERT ... ON DUPLICATE KEY UPDATE。

## 分库分表
- 垂直拆分（按业务模块）vs 水平拆分（按分片键散列/范围）。
- 分片键选择：数据均匀性、查询路由效率、避免跨片查询。
- 分布式主键：雪花算法（Snowflake）、号段模式（Leaf）、UUID 性能与索引问题。
- 中间件：ShardingSphere 逻辑表/物理表映射、SQL 改写与路由。
- 跨片聚合：内存合并、流式归并，全局排序成本。

## 读写分离
- 主从复制原理：Binlog 异步/半同步复制，主从延迟的成因与监控。
- 读写分离路由：中间件（ShardingSphere/ProxySQL）vs 应用层注解驱动。
- 主从延迟应对：强制读主库、延迟检测与降级、并行复制优化。

## NoSQL 选型
- Redis：缓存/会话/排行榜/分布式锁，数据全内存、不支持复杂查询。
- MongoDB：文档模型、灵活 Schema、适合非结构化数据与快速迭代。
- Elasticsearch：全文检索、倒排索引、日志分析场景，写入成本高。
- HBase/Cassandra：海量数据宽表、LSM-Tree、适合高吞吐写入与时序数据。
- 选型维度：数据模型、读写模式、一致性要求、扩展方式、运维复杂度。

## 面试追问模板
- 这条 SQL 走了什么索引？还有优化空间吗？
- 分库分表后，跨片的 JOIN 和聚合查询怎么做？
- 主从延迟 5 秒，业务怎么处理？
- 为什么选这个 NoSQL 而不是 MySQL？迁移成本和风险怎么评估？
