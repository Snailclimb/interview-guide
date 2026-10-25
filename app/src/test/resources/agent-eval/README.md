# Agent 质量评测

## 离线门禁

普通测试和 `agentEvaluation` 会校验 `dataset-v1.jsonl`、引用边界、Markdown 基本格式和
Loop 预算，不调用模型：

```powershell
.\gradlew.bat :app:agentEvaluation --no-daemon
```

## 真实运行结果

将每次 Agent 运行整理为 JSONL，每行至少包含以下字段：

```json
{"sampleId":"review-completeness-001","provider":"pixel","model":"gpt-5.6-luna","promptVersion":"agent-coach-v1","codeVersion":"git-sha","answer":"## 结论\n...","promptTokens":120,"completionTokens":80,"cost":0.00048,"sources":[],"loopIterations":[]}
```

`sampleId` 必须存在于 `dataset-v1.jsonl`；`provider`、`model`、`promptVersion` 和
`codeVersion` 用于比较不同运行；`answer` 会参与评分，但不会写入最终报告；`sources`
和 `loopIterations` 用于检查 RAG 引用和 Loop 边界。

执行真实评测并生成 `JSON + Markdown` 报告：

```powershell
.\gradlew.bat :app:agentEvaluation `
  -PagentEvalInput=build/agent-eval/run.jsonl `
  -PagentEvalReportDir=build/reports/agent-eval `
  -PagentEvalRunId=real-pixel-gpt5-luna-001 `
  --no-daemon
```

报告只保留样本 ID、模型/版本、Token、费用、分数和违规项，不把完整回答写入报告。
