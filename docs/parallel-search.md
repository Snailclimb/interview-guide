# 可选的联网搜索工具

支持工具调用的面试客户端可以使用 [Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)
查找公开技术资料，并按需提取网页内容。使用 Streamable HTTP 连接
`https://search.parallel.ai/mcp`，搜索和提取无需 Parallel 账号或 API Key。
免费服务有使用限制；聊天模型仍使用已有 Provider 配置，其推理费用单独计算。

## 启用

按 README 安装 JDK 25、配置模型和依赖服务，然后在根目录 `.env` 中设置：

```dotenv
APP_AI_PARALLEL_SEARCH_ENABLED=true
```

重启后端：

```bash
./gradlew :app:bootRun
```

服务启动时会连接 MCP 服务器；若无法连接，请检查网络或将该选项恢复为 `false`。
连接超时为 10 秒，初始化和工具请求超时为 30 秒。

支持工具调用的普通面试客户端和语音面试客户端会获得 `parallel_web_search` 和
`parallel_web_fetch`，由模型按需调用。例如，可以在语音面试中请求：
“请查找 Spring AI 官方的 MCP 客户端文档，并根据文档提出一道面试题。”
模型需要支持工具调用；是否调用由模型决定。原有模型选择、Skills 工具和 Advisor 配置保持不变。
不带工具的 Plain 客户端（例如知识库查询及文字方向题生成）不启用这些工具。

此选项默认关闭。启用后，模型生成的搜索词和待提取 URL 会发送给 Parallel；
请仅用于公开技术资料，避免在联网请求中包含简历、私人知识库内容或其他敏感信息。
网页结果属于外部资料，应核对来源，不应当作系统指令。

关闭时将 `.env` 中的选项恢复为 `false` 并重启后端，应用不会创建 Parallel MCP 客户端。
