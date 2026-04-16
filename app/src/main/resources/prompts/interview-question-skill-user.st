# Input Data
请生成共 {questionCount} 个主问题。
每个主问题都必须额外生成 {followUpCount} 个追问。

## 难度级别
{difficultyDescription}

## 面试方向
{skillName}（{skillDescription}）

## Skill Tool 指令
请先调用 Skill 工具：
- command: {skillToolCommand}

## 问题分布要求
| 方向 | 数量 | 说明 |
|------|------|------|
{allocationTable}

## 已考知识点（避免重复）
{historicalSection}

## 参考题库（references）
{referenceSection}

## 职位描述
{jdSection}

## 输出要求
- questions 数组必须包含恰好 {questionCount} 个元素，不多不少
- 每个主问题必须包含 topicSummary 字段：10 字以内的知识点摘要（用于历史去重），格式示例：
  - "Redis RDB/AOF 持久化对比"
  - "MySQL 索引失效场景"
  - "HashMap 扩容机制"
  - "TCP 三次握手流程"
