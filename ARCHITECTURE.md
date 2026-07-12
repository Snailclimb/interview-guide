# AI Interview Platform：架构与数据流导师指南

> 阅读目标：先建立系统地图，再理解模块职责，最后沿着真实业务数据流走一遍。
>
> 推荐顺序：系统全景 → 模块边界 → 核心数据模型 → 简历/RAG/面试/语音链路 → 状态机 → 部署。

## 1. 先用一句话理解系统

这是一个以 Spring Boot 为业务中枢的 AI 面试平台：React 负责交互，PostgreSQL 保存业务事实，pgvector 支撑语义检索，Redis 同时承担缓存、限流和异步任务队列，RustFS 保存原始文件，外部模型提供聊天、向量、ASR 和 TTS 能力。

### 系统用例图：用户到底能做什么

```plantuml
@startuml
left to right direction
skinparam actorStyle awesome
skinparam usecase {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
}

actor "候选人" as Candidate
actor "平台维护者" as Admin
actor "LLM / Embedding\n服务商" as AI

rectangle "AI Interview Platform" {
  usecase "上传并分析简历" as UCResume
  usecase "管理面试知识库" as UCKb
  usecase "RAG 问答" as UCRag
  usecase "进行文本模拟面试" as UCText
  usecase "进行实时语音面试" as UCVoice
  usecase "查看评估报告与历史" as UCReport
  usecase "安排面试日程" as UCSchedule
  usecase "配置模型供应商" as UCProvider
}

Candidate --> UCResume
Candidate --> UCRag
Candidate --> UCText
Candidate --> UCVoice
Candidate --> UCReport
Candidate --> UCSchedule
Admin --> UCKb
Admin --> UCProvider

UCResume ..> AI : 结构化分析
UCKb ..> AI : 生成向量
UCRag ..> AI : 检索 + 生成
UCText ..> AI : 出题 + 评分
UCVoice ..> AI : ASR + 对话 + TTS
@enduml
```

导师视角：用例图先忽略代码。你应该看到两类能力：一类是“资料准备”（简历、知识库、模型配置），另一类是“面试运行”（文本、语音、评估）。前者为后者提供上下文。

## 2. 系统全景：各技术组件如何协作

```plantuml
@startuml
skinparam component {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  FontName Arial
}
skinparam package {
  BackgroundColor #FAFAFA
  BorderColor #cccccc
}

actor "用户" as User

package "Presentation" {
  component "React 18 SPA\nTypeScript + Vite" as Web #96CBFE
}

package "Spring Boot 4.1" {
  component "REST Controllers" as REST #A8D08D
  component "Voice WebSocket" as WS #A8D08D
  component "Business Services" as Services #A8D08D
  component "AI Foundation\nRegistry + Structured Output" as AICore #d5e8d4
  component "Async Workers\nRedis Stream Consumers" as Workers #d5e8d4
  component "Repositories / MapStruct" as Persistence #fff2cc
  component "File / Export Infrastructure" as Infra #fff2cc
}

database "PostgreSQL\nBusiness Data" as PG #F4B183
database "pgvector\n1024-d cosine" as Vector #F4B183
database "Redis\nCache / Rate Limit / Streams" as Redis #ffe6cc
component "RustFS / S3\nOriginal Files" as S3 #e1d5e7
cloud "External AI Providers" as AI #e1d5e7

User --> Web : HTTPS
Web --> REST : JSON REST
Web --> WS : audio / subtitles / control
REST --> Services
WS --> Services
Services --> AICore : chat / embedding
Services --> Persistence
Services --> Infra
Services -> Redis : enqueue / cache / limit
Redis -> Workers : consumer groups
Workers --> AICore
Workers --> Persistence
Persistence --> PG : JPA
Persistence --> Vector : native vector query
Infra --> S3 : S3 API
AICore --> AI : HTTP / WebSocket

note right of Redis
  Redis 不是主数据源：
  它负责“快”和“异步”，
  PostgreSQL 负责“事实”。
end note
@enduml
```

关键边界：

- 前端只通过 `frontend/src/api/` 访问后端，不直接认识数据库或模型供应商。
- Controller 只负责路由、校验和委托；业务编排放在 Service。
- LLM、S3、外部 HTTP 调用不能放进数据库事务，这是系统稳定性的关键规则。
- Redis Stream 把耗时 AI 工作从请求线程移开，让上传接口快速返回 `PENDING`。

## 3. 后端模块地图：代码该去哪里找

```plantuml
@startuml
skinparam package {
  BackgroundColor #FAFAFA
  BorderColor #999999
}
skinparam component {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
}

package "interview.guide" {
  package "common" {
    component "ai\n模型注册、结构化输出、Prompt 安全" as CommonAI #d5e8d4
    component "async\nStream 生产/消费模板" as CommonAsync #d5e8d4
    component "result + exception\n统一响应与业务异常" as CommonWeb #d5e8d4
    component "aspect\n可重复限流" as CommonRate #d5e8d4
    component "evaluation\n统一评估" as CommonEval #d5e8d4
  }

  package "infrastructure" {
    component "file\n校验、解析、清洗、S3" as FileInfra #fff2cc
    component "redis\nStream 与会话缓存" as RedisInfra #fff2cc
    component "mapper\nEntity ↔ DTO" as MapperInfra #fff2cc
    component "export\nPDF 导出" as ExportInfra #fff2cc
  }

  package "modules" {
    component "resume\n简历上传与 AI 分析" as Resume
    component "knowledgebase\n文档向量化与 RAG" as KB
    component "interview\n文本面试与评估" as Interview
    component "voiceinterview\n实时语音面试" as Voice
    component "interviewschedule\n面试日程" as Schedule
    component "llmprovider\n模型供应商配置" as Provider
  }
}

Resume ..> FileInfra
KB ..> FileInfra
Resume ..> CommonAsync
KB ..> CommonAsync
Interview ..> CommonAsync
Voice ..> CommonAsync
Resume ..> CommonAI
KB ..> CommonAI
Interview ..> CommonAI
Voice ..> CommonAI
Interview ..> CommonEval
Voice ..> CommonEval
Resume ..> MapperInfra
KB ..> MapperInfra
Interview ..> MapperInfra
Provider --> CommonAI : 动态提供模型配置
CommonAsync --> RedisInfra
Interview ..> Resume : 使用简历上下文
Voice ..> Resume : 使用简历上下文
@enduml
```

导师视角：`modules/` 按业务垂直切分，`common/` 和 `infrastructure/` 按技术能力横向复用。判断新代码放哪儿时问一句：“这是某个业务独有的规则，还是所有业务都可能使用的技术能力？”

## 4. 核心数据模型：数据库里保存了什么事实

下面是概念模型，刻意省略审计字段和不影响关系理解的属性。

```plantuml
@startuml
hide methods
skinparam class {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  ArrowColor #333333
}

class ResumeEntity #dae8fc {
  id: Long
  originalFilename: String
  storageKey: String
  resumeText: Text
  analyzeStatus: AsyncTaskStatus
}
class ResumeAnalysisEntity #d5e8d4 {
  id: Long
  analysisJson: JSON/Text
  score: Integer
}

class KnowledgeBaseEntity #dae8fc {
  id: Long
  name: String
  fileHash: String
  vectorStatus: VectorStatus
}
class VectorChunk #fff2cc {
  knowledgeBaseId: Long
  content: Text
  embedding: vector(1024)
}
class RagChatSessionEntity #dae8fc {
  id: Long
  title: String
}
class RagChatMessageEntity #d5e8d4 {
  id: Long
  role: String
  content: Text
}

class InterviewSessionEntity #dae8fc {
  id: Long
  status: String
  providerId: String
}
class InterviewAnswerEntity #d5e8d4 {
  id: Long
  question: Text
  answer: Text
  evaluation: Text
}

class VoiceInterviewSessionEntity #dae8fc {
  id: Long
  status: VoiceInterviewSessionStatus
  providerId: String
}
class VoiceInterviewMessageEntity #d5e8d4 {
  id: Long
  userRecognizedText: Text
  aiGeneratedText: Text
}
class VoiceInterviewEvaluationEntity #fff2cc {
  id: Long
  report: Text
}

class InterviewScheduleEntity #e1d5e7 {
  id: Long
  scheduledAt: DateTime
  status: InterviewStatus
}
class LlmProviderEntity #e1d5e7 {
  id: String
  baseUrl: String
  chatModel: String
  embeddingModel: String
  encryptedApiKey: String
}

ResumeEntity "1" *-- "0..*" ResumeAnalysisEntity
KnowledgeBaseEntity "1" *-- "0..*" VectorChunk
RagChatSessionEntity "1" *-- "0..*" RagChatMessageEntity
InterviewSessionEntity "1" *-- "0..*" InterviewAnswerEntity
InterviewSessionEntity "0..*" --> "0..1" ResumeEntity : resume context
VoiceInterviewSessionEntity "1" *-- "0..*" VoiceInterviewMessageEntity
VoiceInterviewSessionEntity "1" *-- "0..1" VoiceInterviewEvaluationEntity
VoiceInterviewSessionEntity "0..*" --> "0..1" ResumeEntity : resume context
InterviewSessionEntity ..> LlmProviderEntity : providerId
VoiceInterviewSessionEntity ..> LlmProviderEntity : providerId
@enduml
```

要抓住的设计思想：原始资料、处理状态和 AI 结果分开保存。这样失败可以重试，历史结果可以保留，业务实体也不必被某一次模型调用绑死。

## 5. 简历上传与分析：典型异步任务链路

```plantuml
@startuml
hide footbox
skinparam sequence {
  ArrowColor #333333
  GroupBackgroundColor #f5f5f5
  GroupBorderColor #cccccc
}

actor "用户" as U
participant "React UploadPage" as FE #96CBFE
participant "ResumeController" as C #A8D08D
participant "ResumeUploadService" as S #A8D08D
participant "FileValidation / Tika" as Parse #fff2cc
participant "RustFS" as S3 #D5A6E6
database "PostgreSQL" as DB #F4B183
participant "Redis Stream" as Redis #ffe6cc
participant "Analyze Consumer" as Worker #d5e8d4
participant "ResumeGradingService" as Grade #d5e8d4
participant "LLM" as AI #D5A6E6

U -> FE : 上传 PDF/DOCX/Markdown
FE -> C : multipart request
C -> S : uploadAndAnalyze(file)
S -> Parse : 校验大小/类型、提取文本、计算去重依据

alt 已存在相同简历
  S -> DB : 查询历史分析
  S --> FE : duplicate=true + 已有状态/结果
else 新简历
  S -> S3 : 保存原始文件
  S -> DB : 保存 Resume(PENDING)
  S ->> Redis : 发布 resumeId + content
  S --> FE : 立即返回 PENDING

  Redis ->> Worker : consumer group 取任务
  Worker -> DB : 状态改为 PROCESSING
  Worker -> Grade : analyzeResume(content)
  Grade -> AI : 结构化分析请求
  AI --> Grade : ResumeAnalysisResponse
  Worker -> DB : 保存分析 + COMPLETED
  alt 调用失败且可重试
    Worker ->> Redis : 带 retryCount 重新入队
  else 最终失败
    Worker -> DB : 状态改为 FAILED + error
  end
end
@enduml
```

这里最值得学习的是“两阶段响应”：HTTP 请求只负责可靠接收资料，耗时且不稳定的 AI 分析交给异步 Worker。前端通过状态轮询感知完成。

## 6. 知识库与 RAG：写入链路和查询链路

### 6.1 文档写入与向量化

```plantuml
@startuml
skinparam activity {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  DiamondBackgroundColor #fff2cc
  DiamondBorderColor #d6b656
}

start
#d5e8d4:接收知识库文件;
:校验大小、MIME 与扩展名;
:计算文件 Hash;
if (Hash 已存在?) then (是)
  #e1d5e7:返回已有知识库记录;
  stop
else (否)
  :Tika 提取并清洗文本;
  :原文件上传 RustFS;
  :保存 KnowledgeBase(PENDING);
  :向 Redis Stream 发布向量化任务;
endif

:Worker 校验实体仍然存在;
if (已被删除?) then (是)
  #f8cecc:ACK 并丢弃任务;
  stop
else (否)
  :切分文本 Chunks;
  :EmbeddingModel 生成 1024 维向量;
  :批量写入 pgvector;
  #d5e8d4:状态改为 COMPLETED;
endif
stop
@enduml
```

### 6.2 RAG 查询

```plantuml
@startuml
hide footbox
actor "用户" as U
participant "KnowledgeBaseQueryPage" as FE #96CBFE
participant "RagChatController" as C #A8D08D
participant "KnowledgeBaseQueryService" as Q #A8D08D
participant "Query Rewrite LLM" as Rewrite #D5A6E6
participant "EmbeddingModel" as Emb #D5A6E6
database "pgvector" as VDB #F4B183
participant "Answer LLM" as LLM #D5A6E6
database "Chat History" as DB #F4B183

U -> FE : 提问
FE -> C : query(question, sessionId)
C -> Q : 查询编排
Q -> DB : 读取近期对话
Q -> Rewrite : 结合上下文改写查询
Rewrite --> Q : standalone query
Q -> Emb : 生成查询向量
Emb --> Q : vector(1024)
Q -> VDB : COSINE 相似度 Top-K 检索
VDB --> Q : 相关文本片段
Q -> LLM : system prompt + context + question
LLM --> Q : grounded answer
Q -> DB : 保存问答消息
Q --> FE : answer + references
FE --> U : 展示回答
@enduml
```

导师视角：RAG 不是“让模型查数据库”，而是应用先检索，再把相关资料拼进 Prompt。检索质量决定“给模型看什么”，生成质量决定“模型如何表达”。

## 7. 文本模拟面试：从建会话到异步评分

```plantuml
@startuml
hide footbox
actor "候选人" as U
participant "InterviewPage" as FE #96CBFE
participant "InterviewController" as C #A8D08D
participant "InterviewSessionService" as Session #A8D08D
participant "InterviewQuestionService" as Question #d5e8d4
participant "LlmProviderRegistry" as Registry #d5e8d4
participant "LLM" as AI #D5A6E6
database "PostgreSQL" as DB #F4B183
participant "Redis Stream" as Redis #ffe6cc
participant "Evaluate Consumer" as Eval #d5e8d4

U -> FE : 创建面试（技能/简历/供应商）
FE -> C : create session
C -> Session : 创建业务会话
Session -> DB : 保存 session
Session -> Question : 生成首题
Question -> Registry : getChatClientOrDefault(provider)
Registry -> AI : Prompt + skill + resume context
AI --> Question : structured question
Question -> DB : 保存题目
Session --> FE : session + first question

loop 每一轮问答
  U -> FE : 提交答案
  FE -> C : submitAnswer
  C -> Session : 保存答案
  Session -> DB : persist answer
  Session -> Question : 根据历史生成下一题
  Question -> AI : conversation context
  AI --> Question : next question
  Session --> FE : next question
end

U -> FE : 结束面试
FE -> C : finish session
C -> DB : 标记待评估
C ->> Redis : 发布 evaluation task
Redis ->> Eval : 消费任务
Eval -> AI : 批量/汇总评估
AI --> Eval : EvaluationReport
Eval -> DB : 保存评分与报告
@enduml
```

注意 `LlmProviderRegistry` 的价值：业务 Service 不自行拼装 SDK 客户端，而是按 provider ID 获取统一配置的 `ChatClient`。切换模型供应商不会扩散到每个业务模块。

## 8. 实时语音面试：最复杂的数据流

```plantuml
@startuml
hide footbox
skinparam sequence {
  ArrowColor #333333
  GroupBackgroundColor #f5f5f5
}

actor "候选人" as U
participant "Browser AudioWorklet" as Audio #96CBFE
participant "Voice WebSocket Handler" as WS #A8D08D
participant "Qwen ASR" as ASR #D5A6E6
participant "VoiceInterviewService" as Service #A8D08D
participant "Voice ChatClient" as LLM #D5A6E6
participant "Qwen TTS" as TTS #D5A6E6
database "PostgreSQL" as DB #F4B183

U -> Audio : 开始/恢复语音面试
Audio -> WS : 建立 /ws/voice-interview/{id}
WS -> Service : 校验会话并加载历史
WS -> ASR : 启动流式转写

loop 一轮对话
  U -> Audio : 说话
  Audio ->> WS : PCM audio chunks
  WS ->> ASR : streaming audio
  ASR -->> WS : partial subtitles
  WS -->> Audio : realtime subtitle
  ASR -->> WS : final transcript

  WS -> WS : 合并分段、去抖、避免重复处理
  WS -> Service : 保存用户文本
  WS -> LLM : 历史 + 简历上下文 + 用户回答

  par 流式文本
    LLM -->> WS : response tokens
    WS -->> Audio : AI subtitles
  else 分句 TTS
    WS ->> TTS : sentence chunks（受 Semaphore 限制）
    TTS -->> WS : PCM results
    WS -> WS : 按原句顺序重排音频块
    WS -->> Audio : WAV audio chunks
  end

  WS -> Service : 保存 AI 文本
  note over WS,Audio
    AI 播放期间及短暂 cooldown
    丢弃麦克风回声，避免 AI 自问自答
  end note
end

alt 用户暂停或 5 分钟无活动
  WS -> Service : pauseSession
  WS -> ASR : stopTranscription
  WS --> Audio : control: paused + close
else 用户完成
  WS -> Service : completeSession
  Service -> DB : COMPLETED
end
@enduml
```

这条链路的难点不是单一 API，而是并发协调：ASR 是持续输入流，LLM 是文本输出流，TTS 可以并行生成但必须按句子顺序播放，同时还要做回声抑制、暂停恢复和断线清理。

## 9. 状态机：异步任务与语音会话如何保持可恢复

### 9.1 通用异步任务状态

```plantuml
@startuml
skinparam state {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  ArrowColor #333333
}

[*] --> PENDING : 保存实体 + 发布任务
state PENDING #dae8fc
state PROCESSING #fff2cc
state COMPLETED #d5e8d4
state FAILED #f8cecc

PENDING --> PROCESSING : consumer 领取
PROCESSING --> COMPLETED : 结果持久化成功
PROCESSING --> PENDING : 可重试异常 / 重新入队
PROCESSING --> FAILED : 超过重试次数
FAILED --> PENDING : 用户手动重试
COMPLETED --> [*]

note right of PROCESSING
  Consumer 处理前再次确认实体存在；
  若已删除则 ACK 丢弃，避免幽灵任务。
end note
@enduml
```

### 9.2 语音面试会话状态

```plantuml
@startuml
skinparam state {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  ArrowColor #333333
}

[*] --> IN_PROGRESS : 创建并连接
state IN_PROGRESS #fff2cc : WebSocket 活跃\nASR/LLM/TTS 管线运行
state PAUSED #dae8fc : 状态已保存\n可以恢复
state COMPLETED #d5e8d4 : 对话结束\n等待/完成评估
state FAILED #f8cecc : 不可恢复错误

IN_PROGRESS --> PAUSED : 用户暂停 / 空闲超时 / 断线保护
PAUSED --> IN_PROGRESS : 恢复连接
IN_PROGRESS --> COMPLETED : 主动完成
IN_PROGRESS --> FAILED : 管线关键错误
PAUSED --> COMPLETED : 用户结束
COMPLETED --> [*]
FAILED --> [*]
@enduml
```

状态字段不是给 UI “显示个标签”而已，它是异步系统恢复、幂等和重试的协议。

## 10. 部署拓扑：本地与生产环境的物理关系

```plantuml
@startuml
skinparam node {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
}
skinparam database {
  BackgroundColor #fff2cc
  BorderColor #d6b656
}
skinparam cloud {
  BackgroundColor #F5F5F5
  BorderColor #cccccc
}

actor "Browser" as Browser

node "Frontend Container" as Front #d5e8d4 {
  artifact "Nginx" as Nginx
  artifact "React static assets" as React
}

node "Application Container\nJava 21" as App #d5e8d4 {
  artifact "Spring Boot app.jar" as Jar
  component "REST :8080" as Rest
  component "WebSocket" as Ws
  component "Stream Consumers" as Consumers
}

node "Data Services" {
  database "PostgreSQL + pgvector" as PG #fff2cc
  database "Redis / Redisson" as Redis #ffe6cc
  database "RustFS (S3 compatible)" as RustFS #fff2cc
}

cloud "External Model Providers" #e1d5e7 {
  node "Chat / Embedding API" as Chat
  node "ASR / TTS WebSocket API" as Speech
}

Browser --> Nginx : HTTPS
Nginx --> React : static files
Nginx --> Rest : /api reverse proxy
Browser --> Ws : WebSocket upgrade
Rest --> PG : JDBC
Consumers --> PG : JPA / vector SQL
Rest --> Redis : cache / rate limit / enqueue
Redis --> Consumers : stream consumer groups
Rest --> RustFS : S3 API
Consumers --> Chat : HTTPS
Ws --> Chat : streaming chat
Ws --> Speech : WebSocket / HTTPS
@enduml
```

本地开发时这些节点由 `docker-compose.dev.yml` 和本地 `bootRun`/Vite 组合启动；容器化部署时前端 Nginx、后端应用和数据服务分别成为独立节点。

## 11. 像导师一样带你读代码

建议按下面四次“纵向切片”阅读，而不是按目录从头读到尾：

1. **第一次：简历上传**

   从 `ResumeController` 进入，跟到 `ResumeUploadService`、文件基础设施、Repository，再沿 Redis Stream 跟到 `AnalyzeStreamConsumer`。你会一次看懂 Controller → Service → Repository、对象存储和异步模板。

2. **第二次：RAG 查询**

   从 `RagChatController` 跟到 `KnowledgeBaseQueryService`、`VectorRepository`、EmbeddingModel 和 ChatClient。重点看“查询改写 → 向量检索 → Prompt 注入上下文”。

3. **第三次：文本面试**

   从 `InterviewController` 跟到 `InterviewSessionService`、`InterviewQuestionService` 和 `EvaluateStreamConsumer`。重点理解会话聚合、出题策略和统一评估。

4. **第四次：语音面试**

   最后读 `VoiceInterviewWebSocketHandler`。它同时涉及状态、并发、流式 I/O 和资源清理，应该在前三条链路都理解后再读。

### 读代码时持续问的五个问题

- 这段代码处理的是业务规则，还是技术细节？它所在目录合理吗？
- 当前操作的主数据源是谁：PostgreSQL、Redis，还是对象存储？
- 这里是同步边界还是异步边界？调用者如何知道最终结果？
- 这里是否存在事务？事务内有没有不稳定的外部调用？
- 失败后系统靠什么恢复：状态字段、重试次数、幂等检查，还是人工重试？

如果你能沿任意一条链路回答这五个问题，就不只是“看懂代码”，而是开始掌握这个系统的设计。

## 12. 全部实体类图与实际示例

### 12.1 先分清五个实体族

```plantuml
@startuml
left to right direction
hide methods
skinparam class {
  BackgroundColor #dae8fc
  BorderColor #6c8ebf
  ArrowColor #374151
  FontName Arial
  FontSize 11
}
skinparam package {
  BackgroundColor #FAFAFA
  BorderColor #cbd5e1
}

package "简历域 resume" #EFF6FF {
  class ResumeEntity #dbeafe;line:3b82f6 {
    +id: Long
    +fileHash: String
    +originalFilename: String
    +fileSize: Long
    +contentType: String
    +storageKey: String
    +storageUrl: String
    +resumeText: Text
    +uploadedAt: LocalDateTime
    +lastAccessedAt: LocalDateTime
    +accessCount: Integer
    +analyzeStatus: AsyncTaskStatus
    +analyzeError: String
  }

  class ResumeAnalysisEntity #bfdbfe;line:2563eb {
    +id: Long
    +overallScore: Integer
    +contentScore: Integer
    +structureScore: Integer
    +skillMatchScore: Integer
    +expressionScore: Integer
    +projectScore: Integer
    +summary: Text
    +strengthsJson: JSON Text
    +suggestionsJson: JSON Text
    +analyzedAt: LocalDateTime
  }
}

package "文本面试域 interview" #F0FDF4 {
  class InterviewSessionEntity #dcfce7;line:16a34a {
    +id: Long
    +sessionId: UUID String
    +skillId: String
    +difficulty: String
    +resumeId: Long
    +totalQuestions: Integer
    +currentQuestionIndex: Integer
    +status: SessionStatus
    +questionsJson: JSON Text
    +overallScore: Integer
    +overallFeedback: Text
    +strengthsJson: JSON Text
    +improvementsJson: JSON Text
    +referenceAnswersJson: JSON Text
    +evaluateStatus: AsyncTaskStatus
    +evaluateError: String
    +llmProvider: String
    +createdAt: LocalDateTime
    +completedAt: LocalDateTime
  }

  class InterviewAnswerEntity #bbf7d0;line:15803d {
    +id: Long
    +questionIndex: Integer
    +question: Text
    +category: String
    +userAnswer: Text
    +score: Integer
    +feedback: Text
    +referenceAnswer: Text
    +keyPointsJson: JSON Text
    +answeredAt: LocalDateTime
  }
}

package "知识库域 knowledgebase" #FFF7ED {
  class KnowledgeBaseEntity #ffedd5;line:f97316 {
    +id: Long
    +fileHash: String
    +name: String
    +category: String
    +originalFilename: String
    +fileSize: Long
    +contentType: String
    +storageKey: String
    +storageUrl: String
    +uploadedAt: LocalDateTime
    +lastAccessedAt: LocalDateTime
    +accessCount: Integer
    +questionCount: Integer
    +vectorStatus: VectorStatus
    +vectorError: String
    +chunkCount: Integer
  }

  class RagChatSessionEntity #fed7aa;line:ea580c {
    +id: Long
    +title: String
    +status: SessionStatus
    +createdAt: LocalDateTime
    +updatedAt: LocalDateTime
    +messageCount: Integer
    +isPinned: Boolean
  }

  class RagChatMessageEntity #fdba74;line:c2410c {
    +id: Long
    +type: MessageType
    +content: Text
    +messageOrder: Integer
    +createdAt: LocalDateTime
    +updatedAt: LocalDateTime
    +completed: Boolean
  }
}

package "语音面试域 voiceinterview" #FAF5FF {
  class VoiceInterviewSessionEntity #f3e8ff;line:9333ea {
    +id: Long
    +userId: String
    +roleType: String
    +skillId: String
    +difficulty: String
    +customJdText: Text
    +resumeId: Long
    +introEnabled: Boolean
    +techEnabled: Boolean
    +projectEnabled: Boolean
    +hrEnabled: Boolean
    +llmProvider: String
    +currentPhase: InterviewPhase
    +status: VoiceInterviewSessionStatus
    +plannedDuration: Integer
    +actualDuration: Integer
    +startTime: LocalDateTime
    +endTime: LocalDateTime
    +pausedAt: LocalDateTime
    +resumedAt: LocalDateTime
    +evaluateStatus: AsyncTaskStatus
    +evaluateError: String
  }

  class VoiceInterviewMessageEntity #e9d5ff;line:7e22ce {
    +id: Long
    +sessionId: Long
    +messageType: String
    +phase: InterviewPhase
    +userRecognizedText: Text
    +aiGeneratedText: Text
    +timestamp: LocalDateTime
    +sequenceNum: Integer
  }

  class VoiceInterviewEvaluationEntity #d8b4fe;line:6b21a8 {
    +id: Long
    +sessionId: Long
    +overallScore: Integer
    +overallFeedback: Text
    +questionEvaluationsJson: JSON Text
    +strengthsJson: JSON Text
    +improvementsJson: JSON Text
    +referenceAnswersJson: JSON Text
    +interviewerRole: String
    +interviewDate: LocalDateTime
  }
}

package "配置与日程" #F8FAFC {
  class LlmProviderEntity #e2e8f0;line:64748b {
    +id: String
    +baseUrl: String
    +apiKeyCiphertext: String
    +apiKeyNonce: String
    +model: String
    +embeddingModel: String
    +embeddingDimensions: Integer
    +supportsEmbedding: boolean
    +temperature: Double
    +enabled: boolean
    +builtin: boolean
  }

  class LlmGlobalSettingEntity #e2e8f0;line:64748b {
    +id: Long = 1
    +defaultChatProviderId: String
    +defaultEmbeddingProviderId: String
    +createdAt: LocalDateTime
    +updatedAt: LocalDateTime
  }

  class InterviewScheduleEntity #f1f5f9;line:475569 {
    +id: Long
    +companyName: String
    +position: String
    +interviewTime: LocalDateTime
    +interviewType: String
    +meetingLink: Text
    +roundNumber: Integer
    +interviewer: String
    +notes: Text
    +status: InterviewStatus
  }
}

ResumeEntity "1" *-- "0..*" ResumeAnalysisEntity : JPA / 历次分析
ResumeEntity "0..1" <-- "0..*" InterviewSessionEntity : JPA / 可选简历
InterviewSessionEntity "1" *-- "0..*" InterviewAnswerEntity : JPA / answers
RagChatSessionEntity "0..*" -- "0..*" KnowledgeBaseEntity : JPA / join table
RagChatSessionEntity "1" *-- "0..*" RagChatMessageEntity : JPA / messages

VoiceInterviewSessionEntity "1" ..> "0..*" VoiceInterviewMessageEntity : sessionId / 逻辑关联
VoiceInterviewSessionEntity "1" ..> "0..1" VoiceInterviewEvaluationEntity : sessionId / 逻辑关联
VoiceInterviewSessionEntity "0..*" ..> "0..1" ResumeEntity : resumeId / 逻辑关联
LlmGlobalSettingEntity ..> LlmProviderEntity : 默认 chat/embedding ID
InterviewSessionEntity ..> LlmProviderEntity : llmProvider 字符串
VoiceInterviewSessionEntity ..> LlmProviderEntity : llmProvider 字符串

note bottom of InterviewScheduleEntity
  当前是独立日程记录，
  没有直接关联模拟面试会话。
end note

legend right
  |= 线型 |= 含义 |
  | 实线/菱形 | JPA 显式对象关联 |
  | 虚线 | 只通过 ID 或字符串逻辑关联 |
endlegend
@enduml
```

### 12.2 简历域：文件本体与分析结果分开

#### `ResumeEntity`：一次上传的简历文件

实际例子：张三上传了 `张三-Java后端-5年.pdf`。原文件在 RustFS，解析后的纯文本和异步分析状态保存在这条记录中。

```json
{
  "id": 101,
  "fileHash": "9f26...a81c",
  "originalFilename": "张三-Java后端-5年.pdf",
  "fileSize": 428516,
  "contentType": "application/pdf",
  "storageKey": "resumes/2026/07/101.pdf",
  "resumeText": "张三，5年Java开发经验……",
  "accessCount": 3,
  "analyzeStatus": "COMPLETED"
}
```

它回答的是：**“上传了哪份文件，现在处理到哪一步？”** 文件 Hash 用于去重；它本身不是评分报告。

#### `ResumeAnalysisEntity`：某次 AI 简历评分

实际例子：对简历 `101` 的一次分析得到 82 分。以后重新分析，可以再产生一条结果，因此是多对一关系。

```json
{
  "id": 501,
  "resumeId": 101,
  "overallScore": 82,
  "contentScore": 21,
  "structureScore": 17,
  "skillMatchScore": 20,
  "expressionScore": 12,
  "projectScore": 12,
  "summary": "Java基础扎实，有微服务项目经验",
  "strengthsJson": ["Spring生态", "高并发项目"],
  "suggestionsJson": ["补充量化指标", "突出架构决策"]
}
```

它回答的是：**“AI 如何评价这份简历？”** 将结果单独建表，才能保存历史分析。

### 12.3 文本面试域：一场面试包含多条回答

#### `InterviewSessionEntity`：整场文本模拟面试

实际例子：张三基于简历 `101`，进行一场高级 Java 后端面试，共 8 题，目前已经完成并进入异步评估。

```json
{
  "id": 201,
  "sessionId": "c8c6d45a-88fc-4d19-a934-64af91d4df37",
  "skillId": "java-backend",
  "difficulty": "senior",
  "resumeId": 101,
  "totalQuestions": 8,
  "currentQuestionIndex": 8,
  "status": "COMPLETED",
  "overallScore": 84,
  "evaluateStatus": "COMPLETED",
  "llmProvider": "siliconflow"
}
```

它是聚合根，回答：**“这整场面试是什么配置、进行到哪里、最终表现如何？”**

#### `InterviewAnswerEntity`：某一道题及其回答和评分

实际例子：会话 `201` 的第 3 题询问 Redis 缓存穿透，用户回答后得到 86 分。

```json
{
  "id": 20303,
  "sessionId": 201,
  "questionIndex": 3,
  "question": "缓存穿透是什么？如何治理？",
  "category": "Redis",
  "userAnswer": "不存在的Key会持续落到数据库，可以使用布隆过滤器和空值缓存……",
  "score": 86,
  "feedback": "方案正确，可补充空值缓存的过期策略",
  "referenceAnswer": "布隆过滤器、参数校验、缓存空对象……",
  "keyPointsJson": ["定义", "风险", "布隆过滤器", "空值缓存"]
}
```

它回答：**“具体哪道题答了什么、得了多少分？”** `(session_id, question_index)` 有唯一约束，避免同一题重复落库。

### 12.4 知识库域：资料、聊天和消息是三种不同对象

#### `KnowledgeBaseEntity`：一份可供检索的资料

实际例子：维护者上传了《Java并发面试手册》，切成 126 个向量片段。

```json
{
  "id": 301,
  "fileHash": "31bc...e972",
  "name": "Java并发面试手册",
  "category": "Java面试",
  "originalFilename": "java-concurrency-guide.pdf",
  "fileSize": 2384192,
  "contentType": "application/pdf",
  "storageKey": "knowledge/301.pdf",
  "accessCount": 18,
  "questionCount": 42,
  "vectorStatus": "COMPLETED",
  "chunkCount": 126
}
```

它保存文档元数据和向量化状态。真正的 1024 维向量由 `VectorRepository` 管理，不是一个 JPA Entity。

#### `RagChatSessionEntity`：围绕若干知识库的一次对话

实际例子：用户创建“Java并发复习”会话，同时选择知识库 `301` 和“JVM调优手册”。

```json
{
  "id": 401,
  "title": "Java并发复习",
  "status": "ACTIVE",
  "knowledgeBaseIds": [301, 302],
  "messageCount": 6,
  "isPinned": true
}
```

它回答：**“这一串问答属于哪个主题，并允许检索哪些资料？”** 一场 RAG 会话可选多个知识库，一个知识库也可被多场会话使用。

#### `RagChatMessageEntity`：RAG 对话中的一条消息

实际例子：会话 `401` 的第 4 条消息是 AI 根据检索片段生成的回答。

```json
{
  "id": 4004,
  "sessionId": 401,
  "type": "ASSISTANT",
  "content": "volatile 保证可见性和有序性，但不保证复合操作的原子性……",
  "messageOrder": 4,
  "completed": true
}
```

流式回答期间 `completed=false`，输出结束后变成 `true`。因此消息记录可以表示“正在生成”。

### 12.5 语音面试域：会话、对话片段、最终报告

#### `VoiceInterviewSessionEntity`：一场实时语音面试

实际例子：张三进行 30 分钟高级 Java 后端语音面试，技术和项目阶段开启，HR 阶段关闭。

```json
{
  "id": 601,
  "userId": "user-zhangsan",
  "roleType": "JAVA_BACKEND",
  "skillId": "java-backend",
  "difficulty": "senior",
  "resumeId": 101,
  "introEnabled": true,
  "techEnabled": true,
  "projectEnabled": true,
  "hrEnabled": false,
  "llmProvider": "siliconflow",
  "currentPhase": "PROJECT",
  "status": "IN_PROGRESS",
  "plannedDuration": 30,
  "actualDuration": 18
}
```

它回答：**“实时面试当前处于哪个阶段和生命周期状态？”** `resumeId` 只是 Long 字段，不是 JPA 的 `ResumeEntity` 对象关联。

#### `VoiceInterviewMessageEntity`：一次候选人回答与 AI 追问记录

实际例子：技术阶段第 5 轮，ASR 识别出用户回答，随后 AI 生成追问。

```json
{
  "id": 6105,
  "sessionId": 601,
  "messageType": "USER_SPEECH",
  "phase": "TECH",
  "userRecognizedText": "线程池的核心参数包括核心线程数、最大线程数……",
  "aiGeneratedText": "如果任务队列已满，接下来会发生什么？",
  "sequenceNum": 5,
  "timestamp": "2026-07-10T14:32:18"
}
```

这里一条记录可能同时承载用户识别文本和 AI 后续文本。它通过 `sessionId` 逻辑关联会话，没有 JPA 级联关系。

#### `VoiceInterviewEvaluationEntity`：整场语音面试的最终报告

实际例子：会话 `601` 结束后异步生成唯一一份评估结果。

```json
{
  "id": 6201,
  "sessionId": 601,
  "overallScore": 81,
  "overallFeedback": "技术基础扎实，回答结构清晰，但容量评估不够量化",
  "questionEvaluationsJson": [{"sequence": 1, "score": 84}],
  "strengthsJson": ["Java并发", "故障排查"],
  "improvementsJson": ["量化性能指标", "补充取舍分析"],
  "referenceAnswersJson": ["线程池拒绝策略包括……"],
  "interviewerRole": "JAVA_BACKEND",
  "interviewDate": "2026-07-10T14:00:00"
}
```

`session_id` 有唯一约束，所以一场语音会话只有一份当前评估报告。

### 12.6 模型配置域：供应商清单和全局默认值分开

#### `LlmProviderEntity`：一个可调用的模型供应商配置

实际例子：配置一个兼容 OpenAI API 的 SiliconFlow 供应商，同时提供聊天和 1024 维 Embedding。

```json
{
  "id": "siliconflow",
  "baseUrl": "https://api.siliconflow.example/v1",
  "apiKeyCiphertext": "<AES-GCM密文>",
  "apiKeyNonce": "<随机Nonce>",
  "model": "Qwen3-32B",
  "embeddingModel": "BAAI/bge-m3",
  "embeddingDimensions": 1024,
  "supportsEmbedding": true,
  "temperature": 0.7,
  "enabled": true,
  "builtin": true
}
```

API Key 不以明文保存。它回答：**“怎么连接某个模型服务，它支持什么能力？”**

#### `LlmGlobalSettingEntity`：全系统默认选哪个供应商

实际例子：普通聊天默认用 `siliconflow`，向量生成默认用 `dashscope`。

```json
{
  "id": 1,
  "defaultChatProviderId": "siliconflow",
  "defaultEmbeddingProviderId": "dashscope"
}
```

这是固定 `id=1` 的单例配置。它不复制供应商参数，只保存两个 Provider ID。

### 12.7 日程域：现实中的一次面试安排

#### `InterviewScheduleEntity`：日历上的一个面试事件

实际例子：张三下周参加某公司的二轮视频面试。

```json
{
  "id": 701,
  "companyName": "示例科技",
  "position": "高级Java开发工程师",
  "interviewTime": "2026-07-15T14:00:00",
  "interviewType": "VIDEO",
  "meetingLink": "https://meeting.example/abc",
  "roundNumber": 2,
  "interviewer": "李经理",
  "notes": "重点准备系统设计和项目复盘",
  "status": "PENDING"
}
```

它是一个独立日历事项，目前没有外键连接文本面试或语音面试。不要把“现实招聘面试安排”和“平台里的模拟面试会话”混为一类。

### 12.8 最容易混淆的名字

| 类 | 它代表什么 | 不代表什么 |
|---|---|---|
| `ResumeEntity` | 上传文件及处理状态 | AI 评分结果 |
| `ResumeAnalysisEntity` | 某次简历评分 | 原始简历文件 |
| `InterviewSessionEntity` | 一整场文本模拟面试 | 单道题的回答 |
| `InterviewAnswerEntity` | 一道题、回答和评分 | 整场面试报告 |
| `KnowledgeBaseEntity` | 一份可向量化资料 | 一次 RAG 对话 |
| `RagChatSessionEntity` | 一组连续 RAG 问答 | 某一条消息 |
| `RagChatMessageEntity` | 用户或 AI 的一条消息 | 知识库文档 |
| `VoiceInterviewSessionEntity` | 语音会话生命周期 | 音频文件本身 |
| `VoiceInterviewMessageEntity` | 一轮识别文本/AI 文本 | 最终综合报告 |
| `VoiceInterviewEvaluationEntity` | 整场语音面试报告 | 每个音频分片 |
| `LlmProviderEntity` | 某家模型服务的连接配置 | 当前默认选择 |
| `LlmGlobalSettingEntity` | 默认 chat/embedding Provider ID | API Key 与模型详情 |
| `InterviewScheduleEntity` | 现实面试日程 | 平台模拟面试会话 |
