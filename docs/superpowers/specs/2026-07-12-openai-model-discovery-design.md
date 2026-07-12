# OpenAI 兼容模型发现设计

## 目标

在新增或编辑 LLM Provider 时，用户可以使用当前填写的 `baseUrl` 与 API Key 获取模型列表，并从下拉框选择聊天模型和 Embedding 模型。

## 范围

- 支持遵循 OpenAI Models API 格式的 `GET /models`。
- 兼容返回体 `{ "object": "list", "data": [{ "id": "..." }] }` 的供应商，例如 SiliconFlow。
- 发现操作仅使用临时输入的 Key，不写入数据库、`.env`、`application.yml` 或日志。
- 发现失败不阻止用户手动填写模型名并保存 Provider。

不在本次范围内：

- 不为厂商私有模型列表协议增加适配器。
- 不根据模型名称推断 chat、embedding、image 或 audio 能力。
- 不把模型列表持久化或缓存到数据库。
- 不改变 Provider 创建、更新、加密或默认 Provider 的现有语义。

## 用户体验

1. 用户在 Provider 弹窗输入 Base URL 与 API Key。
2. 用户点击“获取模型”。
3. 页面显示加载状态；按钮防重复点击。
4. 成功后，`model` 与 `embeddingModel` 改为可搜索的下拉选择，并同时保留“手动输入”能力。
5. 同一列表可供两个字段选择，因为 OpenAI `/models` 响应没有统一可靠的能力分类。
6. 失败时显示明确提示，已填写的字段保持不变，用户仍可手动配置并保存。

默认 Base URL 继续由当前 Provider 表单控制；选择 SiliconFlow 时使用现有默认值 `https://api.siliconflow.cn/v1`。

## API 契约

新增端点：

```text
POST /api/llm-provider/models/discover
```

请求：

```json
{
  "baseUrl": "https://api.siliconflow.cn/v1",
  "apiKey": "sk-..."
}
```

成功响应：

```json
{
  "models": [
    "Pro/zai-org/GLM-4.7",
    "Qwen/Qwen3-Embedding-0.6B"
  ]
}
```

Provider 上游请求：

```text
GET {normalizedBaseUrl}/models
Authorization: Bearer {apiKey}
Accept: application/json
```

后端只接受 JSON 根对象中的 `data` 数组；数组元素的非空字符串 `id` 被提取、去重、按字典序排序后返回。不存在可用 `id` 时返回业务错误，而不是空成功结果。

## 安全与错误处理

- 复用现有 Provider 连通性测试的 URL 校验规则与 `ClientHttpRequestFactoryBuilder`，阻止 loopback、link-local、site-local、multicast 和其他内网目标，防止 SSRF。
- 仅接受 `https` Base URL；本地开发的 `http://localhost` 不在发现接口范围内，仍可手动填写模型。
- Base URL 经过规范化后追加单一 `models` 路径，不能由用户提供任意请求路径。
- 请求超时为 5 秒，最大接收 200 个模型 ID。
- API Key 不能进入日志、异常消息、DTO 响应或持久化层。
- 401/403 映射为“API Key 无效或无模型列表权限”；上游超时、429 与 5xx 映射为可重试的用户提示；格式不兼容则提示“供应商未返回 OpenAI Models API 格式”。

## 后端设计

新增不可变 request/response record：

```java
public record DiscoverModelsRequest(@NotBlank String baseUrl, @NotBlank String apiKey) {}

public record DiscoverModelsResponse(List<String> models) {}
```

`LlmProviderController` 新增 `POST /models/discover`，设置与创建 Provider 相同的严格限流级别。

`LlmProviderConfigService` 新增 `discoverModels(DiscoverModelsRequest request)`：

1. 复用/抽取现有安全 URL 验证与基础 URL 标准化。
2. 调用规范化的 `{baseUrl}/models`。
3. 解析 OpenAI 样式 `data[].id`。
4. 返回排序后的模型 ID。

该方法不加数据库事务；它是外部 HTTP 调用，不得置于 `@Transactional` 范围内。

## 前端设计

`frontend/src/types/llmProvider.ts` 新增发现请求与响应类型；`frontend/src/api/llmProvider.ts` 新增 `discoverModels()`。

`SettingsPage.tsx` 的 Provider 表单增加：

- `discoveredModels: string[]`
- `discoveringModels: boolean`
- `modelDiscoveryError: string | null`

“获取模型”仅在 `baseUrl` 与 `apiKey` 非空时可用。成功后在聊天模型和 Embedding 模型输入框旁提供下拉选择；下拉不覆盖用户已经输入的值，只有用户主动选择才更新表单值。

编辑已有 Provider 时，若用户未重新填写 API Key，不展示可用的发现按钮，因为前端无法读取明文 Key；用户可保留原模型、输入新 Key 后发现，或继续手动输入。

## 测试

后端单元测试覆盖：

- 正确解析、去重和排序 `data[].id`。
- 401/403、超时、上游 429/5xx 的用户可读错误。
- 非 OpenAI 格式响应和无有效 ID 响应。
- 内网、非 HTTPS 与带危险路径的 Base URL 拒绝。
- 发现方法不写入 Provider Repository。

前端测试覆盖：

- Base URL 或 API Key 缺失时按钮禁用。
- 成功响应填充两个模型选择控件。
- 用户选择后正确更新创建/更新表单。
- 失败信息可见且手动输入仍可用。

验收：运行 `./gradlew :app:test --no-daemon` 与 `cd frontend && pnpm run build`。
