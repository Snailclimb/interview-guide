# Input Data
请生成共 {questionCount} 个针对项目经历的面试主问题。
每个主问题必须额外生成 {followUpCount} 个追问。

## 面试方向
{skillName}（{skillDescription}）

## 难度级别
{difficultyDescription}

## 候选人简历
---简历内容开始---
{resumeText}
---简历内容结束---

## 已考知识点（避免重复）
{historicalSection}

## 输出要求
- questions 数组必须包含恰好 {questionCount} 个元素，不多不少
- 每个主问题必须包含 topicSummary 字段：10 字以内的知识点摘要（用于历史去重），格式示例：
  - "Redis 缓存策略设计"
  - "MySQL 索引优化"
  - "Kafka 异步消息解耦"
