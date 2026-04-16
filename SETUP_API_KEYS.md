# 🔑 API 密钥配置指南

语音面试功能需要配置以下 API 密钥才能正常工作。

## 📋 必需的 API 服务

### 阿里云百炼 AI (DashScope)

**用途**:
- **LLM 对话**: 生成面试问题、评估回答
- **ASR 语音识别**: 将用户语音实时转换为文本（qwen3-asr-flash-realtime）
- **TTS 语音合成**: 将 AI 回答实时转换为语音（qwen3-tts-flash-realtime）

**统一 API Key**: 一个密钥即可使用所有功能，无需分别申请！

**获取步骤**:
1. 访问 [阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 登录/注册阿里云账号
3. 开通 DashScope 服务（有免费额度）
4. 创建 API Key
5. 复制 API Key

**配置变量**:
```bash
AI_BAILIAN_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

**费用**:
- 新用户有免费额度
- LLM（qwen-plus 模型）: 约 ¥0.008/千 tokens
- ASR 语音识别: ¥2.4/小时（实际使用流式服务）
- TTS 语音合成: ¥2/百万字符

---

## ⚙️ 配置步骤

### 方式 1: 使用 .env 文件（推荐）

1. 复制示例配置文件：
```bash
cp .env.example .env
```

2. 编辑 `.env` 文件，填入您的实际密钥：
```bash
# 使用您自己的密钥替换以下占位符
AI_BAILIAN_API_KEY=sk-your-actual-key-here

# 可选：选择不同的 LLM 模型
AI_MODEL=qwen-plus  # 默认值，也可改为 qwen-max、qwen-long 等
```

3. 启动应用时会自动读取 `.env` 文件

### 方式 2: 使用环境变量

```bash
# Linux/Mac
export AI_BAILIAN_API_KEY=sk-your-key
export AI_MODEL=qwen-plus  # 可选

# Windows PowerShell
$env:AI_BAILIAN_API_KEY="sk-your-key"
$env:AI_MODEL="qwen-plus"  # 可选
```

### 方式 3: 在 IDE 中配置

**IDEA**:
1. Run → Edit Configurations
2. 选择 Spring Boot 配置
3. Environment variables 中添加上述变量

**VS Code**:
1. 创建 `.vscode/launch.json`
2. 添加 env 配置

---

## 🔍 验证配置

启动应用后，检查日志：

```
✅ 成功日志示例:
QwenAsrService initialized with model: qwen3-asr-flash-realtime
QwenTtsService initialized with model: qwen3-tts-flash-realtime, voice: Cherry
DashScope LLM service initialized

❌ 失败日志示例:
WebSocket failed: Expected HTTP 101 response but was '401 Unauthorized'
（说明 API Key 无效或未配置）
```

---

## 💡 成本优化建议

1. **使用免费额度**: 新用户都有免费试用额度
2. **限制并发**: 配置 `rate-limit` 参数控制并发数
3. **选择合适模型**:
   - 开发测试用 `qwen-turbo`（更便宜）
   - 生产环境用 `qwen-plus`（效果更好）
4. **控制面试时长**: 通过 `plannedDuration` 参数限制面试时长

---

## 🆘 常见问题

**Q: 必须使用阿里云吗？**

A: 目前 LLM 支持多家提供商（DashScope/MiniMax/OpenAI/DeepSeek），但语音服务使用的是 Qwen3 实时语音模型，需要阿里云 DashScope API Key。

**Q: 如何降低成本？**

A: 1) 使用 `qwen-turbo` 模型；2) 限制面试时长；3) 添加用户配额限制

**Q: API 密钥会泄露吗？**

A: `.env` 文件已加入 `.gitignore`，不会提交到 Git。请妥善保管您的密钥。

**Q: 测试时需要付费吗？**

A: 阿里云新用户有免费额度，足够测试使用。正式上线后再考虑付费。

**Q: 一个 API Key 真的够用吗？**

A: 是的！项目已升级到 Qwen3 实时语音模型，LLM、ASR、TTS 共用一个 DashScope API Key，无需分别申请。

---

## 📞 获取帮助

- 阿里云文档: https://help.aliyun.com/
- DashScope 文档: https://help.aliyun.com/zh/dashscope/
- Qwen3 实时语音文档: https://help.aliyun.com/zh/model-studio/realtime-api-reference
