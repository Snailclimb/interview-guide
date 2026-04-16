# 消息队列面试重点（模型 + 可靠性 + 主流 MQ 对比）

## 消息模型
- 点对点（Queue）：一条消息只能被一个消费者消费，消费后删除。
- 发布订阅（Topic）：一条消息可被多个订阅者消费，消费独立。
- 消费组（Consumer Group）：同一组内竞争消费、不同组间广播消费。
- 消息顺序性：全局有序（单 Partition）vs 分区有序（同一 key 路由到同一 Partition）。

## 投递语义
- 至多一次（At Most Once）：可能丢消息，性能最高，适用允许丢失的场景（监控指标）。
- 至少一次（At Least Once）：可能重复，需要消费端幂等，是大多数 MQ 的默认语义。
- 恰好一次（Exactly Once）：Kafka 事务 + 幂等 Producer，RocketMQ 事务消息。
- 幂等消费：唯一键去重（Redis/数据库唯一约束）、业务状态机判断。

## 可靠性保障
- 生产端确认：Kafka `acks=all`、RabbitMQ Publisher Confirm、RocketMQ 同步刷盘。
- 消费端确认：手动 ACK（避免自动确认后处理失败丢消息）。
- 消息持久化：磁盘持久化（Kafka 日志段、RocketMQ CommitLog）、副本机制。
- 死信队列（DLQ）：消费重试达到上限后转入死信，人工介入处理。

## 消费积压处理
- 积压原因：消费者宕机、处理变慢（下游依赖超时）、突发流量。
- 应急处理：紧急扩容消费者、临时消费者绕过慢逻辑先落库、消息降级。
- 长期预防：消费监控告警（Lag 阈值）、容量预估、消费幂等保障安全扩容。
- 削峰填谷：MQ 作为缓冲层，上游快速写入、下游匀速消费，保护下游系统。

## 主流 MQ 对比
- Kafka：高吞吐、顺序磁盘写、零拷贝、适合日志/事件流、Pull 模式。
- RocketMQ：事务消息、延迟消息、消息过滤（Tag/SQL92）、适合电商金融、Push/Pull。
- RabbitMQ：AMQP 协议、路由灵活（Exchange 类型丰富）、适合任务分发、Push 模式。
- 选型维度：吞吐量、延迟、消息可靠性、顺序性、运维复杂度、社区生态。

## 消息中间件架构
- Kafka：Broker + Topic + Partition + Replica，Leader-Follower ISR 机制。
- RocketMQ：NameServer + Broker + CommitLog + ConsumeQueue，高性能顺序写。
- 分区/队列设计：分区数影响并行度、分区迁移与再平衡（Rebalance）、消费者组变更。

## 面试追问模板
- 消息积压了 100 万条，怎么处理？多久能恢复？
- 如何保证消息不丢、不重、有序？你的项目怎么做的？
- Kafka 和 RocketMQ 在你项目中怎么选的？用过什么特性？
- 消费者 Rebalance 期间消息怎么处理？会造成重复消费吗？
