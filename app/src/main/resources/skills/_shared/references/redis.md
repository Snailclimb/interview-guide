# Redis 面试重点

## 数据类型与场景
- 五种基础类型：String/Hash/List/Set/ZSet，各类型底层编码与适用场景。
- 特殊类型：Bitmap（活跃统计）、HyperLogLog（UV 去重）、Stream（消息队列）。
- ZSet 底层为什么用跳表而不是红黑树/B+树（范围查询、实现简单、内存灵活）。

## 持久化与线程模型
- RDB（fork + COW）vs AOF（写后日志、fsync 策略），混合持久化。
- Redis 6.0 前单线程模型（避免锁竞争、IO 多路复用），6.0 后多线程 IO（命令执行仍单线程）。

## 生产问题
- 缓存穿透（布隆过滤器/空值缓存）、缓存击穿（互斥锁/永不过期）、缓存雪崩（随机过期/多级缓存）。
- 缓存与数据库一致性：延迟双删、Canal 监听 Binlog、最终一致性方案。

## 分布式锁
- `SET key value NX EX` 基本实现，误删问题与 Lua 原子释放。
- Redisson 可重入锁原理（Hash 结构 + Lua 脚本），看门狗续期机制。
- 集群下可靠性：RedLock 争议与替代方案（fencing token）。

## 性能优化
- Pipeline 批量减少 RTT，Lua 脚本保证原子性。
- BigKey 检测与拆分（redis-rdb-tools、UNLINK 异步删除）。
- HotKey 发现与本地缓存 + 热点分散。
- 内存淘汰策略：allkeys-lru vs volatile-lru，内存碎片清理。

## 集群
- 主从复制（全量 + 增量）、哨兵模式（故障转移、主观/客观下线）。
- Cluster 模式：16384 槽位、Gossip 协议、重定向（MOVED/ASK）。

## 面试追问模板
- 如果 Redis 宕机，业务怎么降级？
- 百万级 QPS 场景下，缓存架构怎么设计？
- 大 Key 线上清理导致阻塞怎么办？
