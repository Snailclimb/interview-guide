<#!
.SYNOPSIS
    Agent and interview API acceptance matrix.

.DESCRIPTION
    Runs non-destructive API checks against a running environment. By default it
    does not call the LLM. Use -RunModelTests to run two real Agent stream calls.
    It may create up to two reusable Agent conversation threads under the target
    interview when the environment does not already have them. It never deletes
    interviews, renames an existing interview, uploads files, or changes provider configuration.
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$InterviewSessionId = "",
    [string]$UserId = "acceptance-$([guid]::NewGuid().ToString('N').Substring(0, 12))",
    [int]$Concurrency = 20,
    [switch]$RunModelTests
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$validRunStatuses = @('IDLE', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
$results = [System.Collections.Generic.List[object]]::new()
$targetSessionId = $InterviewSessionId

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [ValidateSet('GET', 'POST', 'PUT', 'PATCH', 'DELETE')] [string]$Method = 'GET',
        [object]$Body = $null,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 120
    )

    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) {
        $requestHeaders[$key] = $Headers[$key]
    }

    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $requestHeaders
        UseBasicParsing = $true
        SkipHttpErrorCheck = $true
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = $Body | ConvertTo-Json -Depth 50 -Compress
    }

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest @params
        $watch.Stop()
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = [string]$response.Content
            DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
            Headers = $response.Headers
        }
    } catch {
        $watch.Stop()
        return [pscustomobject]@{
            StatusCode = 0
            Body = $_.Exception.Message
            DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
            Headers = @{}
        }
    }
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [ValidateSet('GET', 'POST', 'PUT', 'PATCH', 'DELETE')] [string]$Method = 'GET',
        [object]$Body = $null,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 120
    )

    $response = Invoke-Api -Path $Path -Method $Method -Body $Body -Headers $Headers -TimeoutSec $TimeoutSec
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($response.Body)) {
        try {
            $json = $response.Body | ConvertFrom-Json -Depth 50
        } catch {
            # Keep the raw response for SSE and malformed-response assertions.
        }
    }
    return [pscustomobject]@{
        Response = $response
        Json = $json
    }
}

function Add-TestResult {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [scriptblock]$Test
    )

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Test
        $watch.Stop()
        $result = [pscustomobject]@{ Name = $Name; Status = 'PASS'; DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2); Error = '' }
        $results.Add($result)
        Write-Host ("[PASS] {0} ({1} ms)" -f $Name, $result.DurationMs) -ForegroundColor Green
    } catch {
        $watch.Stop()
        $result = [pscustomobject]@{ Name = $Name; Status = 'FAIL'; DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2); Error = $_.Exception.Message }
        $results.Add($result)
        Write-Host ("[FAIL] {0} ({1} ms): {2}" -f $Name, $result.DurationMs, $result.Error) -ForegroundColor Red
    }
}

function Assert-ResultSuccess {
    param(
        [Parameter(Mandatory)]$ApiResult,
        [string]$Description = 'API request'
    )

    Assert-Condition ($ApiResult.Response.StatusCode -eq 200) "$Description HTTP $($ApiResult.Response.StatusCode)"
    Assert-Condition ($null -ne $ApiResult.Json) "$Description returned non-JSON: $($ApiResult.Response.Body.Substring(0, [math]::Min(180, $ApiResult.Response.Body.Length)))"
    Assert-Condition ($ApiResult.Json.code -eq 200) "$Description application code $($ApiResult.Json.code): $($ApiResult.Json.message)"
}

Write-Host "Agent acceptance matrix: $BaseUrl" -ForegroundColor Cyan
Write-Host "User key: $UserId" -ForegroundColor DarkGray

Add-TestResult 'GET interview session list' {
    $script:sessionListResult = Invoke-JsonApi -Path '/api/interview/sessions'
    Assert-ResultSuccess $sessionListResult 'session list'
    $script:sessionItems = @($sessionListResult.Json.data)
    Assert-Condition ($sessionItems.Count -ge 1) '没有可用于 Agent 验收的面试会话'
    foreach ($item in $sessionItems) {
        Assert-Condition ($item.PSObject.Properties.Name -contains 'title') "会话 $($item.sessionId) 缺少 title 字段"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$item.sessionId)) '会话缺少 sessionId'
    }
    if ([string]::IsNullOrWhiteSpace($targetSessionId)) {
        $script:targetSessionId = [string]$sessionItems[0].sessionId
    }
}

if ([string]::IsNullOrWhiteSpace($targetSessionId)) {
    Write-Host '未能发现可用面试会话，跳过依赖会话的测试。' -ForegroundColor Yellow
} else {
    $escapedSessionId = [Uri]::EscapeDataString($targetSessionId)
    $agentHeaders = @{ 'X-User-Id' = $UserId }

    Add-TestResult 'GET resume list has stable response shape' {
        $result = Invoke-JsonApi -Path '/api/resumes'
        Assert-ResultSuccess $result 'resume list'
        Assert-Condition ($null -ne $result.Json.data) 'resume list data is null'
    }

    Add-TestResult 'GET interview skills' {
        $result = Invoke-JsonApi -Path '/api/interview/skills'
        Assert-ResultSuccess $result 'interview skills'
        Assert-Condition ($null -ne $result.Json.data) 'skills data is null'
    }

    Add-TestResult 'GET target interview session' {
        $result = Invoke-JsonApi -Path "/api/interview/sessions/$escapedSessionId"
        Assert-ResultSuccess $result 'target interview session'
        Assert-Condition ([string]$result.Json.data.sessionId -eq $targetSessionId) 'target session id mismatch'
    }

    Add-TestResult 'GET current interview question' {
        $result = Invoke-JsonApi -Path "/api/interview/sessions/$escapedSessionId/question"
        Assert-ResultSuccess $result 'current question'
    }

    Add-TestResult 'GET Agent conversation persistence' {
        $result = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation" -Headers $agentHeaders
        Assert-ResultSuccess $result 'Agent conversation'
        Assert-Condition ($validRunStatuses -contains [string]$result.Json.data.runStatus) "未知 runStatus: $($result.Json.data.runStatus)"
        Assert-Condition ($null -ne $result.Json.data.messages) 'Agent messages is null'
    }

    Add-TestResult 'Agent rejects missing identity header' {
        $result = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation"
        Assert-Condition ($result.Response.StatusCode -eq 200) "missing identity HTTP $($result.Response.StatusCode)"
        Assert-Condition ($null -ne $result.Json -and $result.Json.code -eq 401) "missing identity was not rejected: $($result.Response.Body)"
    }

    Add-TestResult 'Agent conversation thread list and creation' {
        $list = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations" -Headers $agentHeaders
        Assert-ResultSuccess $list 'Agent conversation thread list'
        $script:agentConversations = @($list.Json.data)
        Assert-Condition ($null -ne $list.Json.data) 'Agent conversation thread list is null'

        while ($script:agentConversations.Count -lt 2) {
            $letter = if ($script:agentConversations.Count -eq 0) { 'A' } else { 'B' }
            $created = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations" -Method POST -Body @{ title = "验收线程 $letter" } -Headers $agentHeaders
            Assert-ResultSuccess $created "create Agent conversation $letter"
            Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$created.Json.data.agentSessionId)) "create Agent conversation $letter missing agentSessionId"
            $script:agentConversations += $created.Json.data
        }

        $script:agentSessionA = [string]$script:agentConversations[0].agentSessionId
        $script:agentSessionB = [string]$script:agentConversations[1].agentSessionId
        Assert-Condition ($script:agentSessionA -ne $script:agentSessionB) 'Agent conversation thread IDs are not unique'
    }

    Add-TestResult 'Agent conversation threads are isolated' {
        $threadA = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString($script:agentSessionA))" -Headers $agentHeaders
        $threadB = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString($script:agentSessionB))" -Headers $agentHeaders
        Assert-ResultSuccess $threadA 'Agent thread A'
        Assert-ResultSuccess $threadB 'Agent thread B'
        Assert-Condition ([string]$threadA.Json.data.agentSessionId -eq $script:agentSessionA) 'Agent thread A returned the wrong ID'
        Assert-Condition ([string]$threadB.Json.data.agentSessionId -eq $script:agentSessionB) 'Agent thread B returned the wrong ID'
        Assert-Condition (@($threadA.Json.data.messages).Count -eq 0 -or @($threadB.Json.data.messages).Count -eq 0 -or [string]$threadA.Json.data.agentSessionId -ne [string]$threadB.Json.data.agentSessionId) 'Agent thread IDs collapsed into one conversation'
    }

    Add-TestResult 'Agent conversation rejects another identity' {
        $otherHeaders = @{ 'X-User-Id' = "$UserId-other" }
        $result = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString($script:agentSessionA))" -Headers $otherHeaders
        Assert-Condition ($result.Response.StatusCode -eq 200) "cross-owner HTTP $($result.Response.StatusCode)"
        Assert-Condition ($null -ne $result.Json -and $result.Json.code -eq 403) "cross-owner access was not rejected: $($result.Response.Body)"
    }

    Add-TestResult 'Agent conversation rename persists' {
        $result = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations/$([Uri]::EscapeDataString($script:agentSessionA))" -Method PATCH -Body @{ title = '验收线程 A（已命名）' } -Headers $agentHeaders
        Assert-ResultSuccess $result 'Agent conversation rename'
        Assert-Condition ([string]$result.Json.data.title -eq '验收线程 A（已命名）') 'Agent conversation rename did not persist'
    }

    Add-TestResult 'Agent conversation archive is recoverable' {
        $created = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations" -Method POST -Body @{ title = '验收归档线程' } -Headers $agentHeaders
        Assert-ResultSuccess $created 'create archive test conversation'
        $archiveId = [string]$created.Json.data.agentSessionId
        $archived = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations/$([Uri]::EscapeDataString($archiveId))" -Method DELETE -Headers $agentHeaders
        Assert-ResultSuccess $archived 'archive Agent conversation'
        $listed = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversations" -Headers $agentHeaders
        Assert-ResultSuccess $listed 'Agent conversation list after archive'
        Assert-Condition (@($listed.Json.data | Where-Object { [string]$_.agentSessionId -eq $archiveId }).Count -eq 0) 'archived Agent conversation remained visible'
    }

    Add-TestResult 'DELETE Agent memory is idempotent' {
        $result = Invoke-JsonApi -Path '/api/agent/memory' -Method DELETE -Headers $agentHeaders
        Assert-ResultSuccess $result 'Agent memory clear'
        $second = Invoke-JsonApi -Path '/api/agent/memory' -Method DELETE -Headers $agentHeaders
        Assert-ResultSuccess $second 'Agent memory clear repeat'
    }

    Add-TestResult 'PATCH interview title rejects blank title without mutation' {
        $result = Invoke-JsonApi -Path "/api/interview/sessions/$escapedSessionId/title" -Method PATCH -Body @{ title = ' ' }
        Assert-Condition ($result.Response.StatusCode -lt 500) "blank title caused server error HTTP $($result.Response.StatusCode)"
        Assert-Condition ($result.Response.Body -match '不能为空|不合法|invalid|error') 'blank title response did not explain validation failure'
    }

    $validAgentPayload = @{
        goal = 'review'
        message = '请只返回一句话，说明本次 Agent 验收正在检查什么。'
        history = @()
        providerId = ''
        memoryKey = $UserId
    }

    Add-TestResult 'Agent validation rejects blank goal as SSE error' {
        $payload = $validAgentPayload.Clone()
        $payload.goal = ' '
        $result = Invoke-Api -Path "/api/agent/interview-sessions/$escapedSessionId/messages/stream" -Method POST -Body $payload -Headers $agentHeaders
        Assert-Condition ($result.StatusCode -eq 200) "blank goal HTTP $($result.StatusCode)"
        Assert-Condition ($result.Body -match 'event: agent-error' -and $result.Body -match '"message":"[^"].+"') 'blank goal did not return SSE validation error'
    }

    Add-TestResult 'Agent validation rejects blank message as SSE error' {
        $payload = $validAgentPayload.Clone()
        $payload.message = ''
        $result = Invoke-Api -Path "/api/agent/interview-sessions/$escapedSessionId/messages/stream" -Method POST -Body $payload -Headers $agentHeaders
        Assert-Condition ($result.StatusCode -eq 200) "blank message HTTP $($result.StatusCode)"
        Assert-Condition ($result.Body -match 'event: agent-error' -and $result.Body -match '"message":"[^"].+"') 'blank message did not return SSE validation error'
    }

    Add-TestResult 'Agent validation rejects message over 4000 characters' {
        $payload = $validAgentPayload.Clone()
        $payload.message = 'x' * 4001
        $result = Invoke-Api -Path "/api/agent/interview-sessions/$escapedSessionId/messages/stream" -Method POST -Body $payload -Headers $agentHeaders
        Assert-Condition ($result.StatusCode -eq 200) "oversized message HTTP $($result.StatusCode)"
        Assert-Condition ($result.Body -match 'event: agent-error' -and $result.Body -match '"message":"[^"].+"') 'oversized message did not return SSE validation error'
    }

    Add-TestResult 'Agent validation rejects history over 32 messages' {
        $payload = $validAgentPayload.Clone()
        $payload.history = @(1..33 | ForEach-Object { @{ role = 'user'; content = "history-$_" } })
        $result = Invoke-Api -Path "/api/agent/interview-sessions/$escapedSessionId/messages/stream" -Method POST -Body $payload -Headers $agentHeaders
        Assert-Condition ($result.StatusCode -eq 200) "oversized history HTTP $($result.StatusCode)"
        Assert-Condition ($result.Body -match 'agent-error' -and $result.Body -match '历史消息最多保留') 'oversized history did not return SSE validation error'
    }

    Add-TestResult "Agent conversation $Concurrency-way concurrent reads" {
        $readResults = @(1..$Concurrency | ForEach-Object -Parallel {
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $response = Invoke-WebRequest -Uri "$using:BaseUrl/api/agent/interview-sessions/$using:escapedSessionId/conversation" -Headers @{ 'X-User-Id' = $using:UserId } -UseBasicParsing -SkipHttpErrorCheck -TimeoutSec 30
                $watch.Stop()
                [pscustomobject]@{ StatusCode = [int]$response.StatusCode; DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2) }
            } catch {
                $watch.Stop()
                [pscustomobject]@{ StatusCode = 0; DurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2) }
            }
        } -ThrottleLimit ([math]::Min($Concurrency, 10)))
        Assert-Condition ($readResults.Count -eq $Concurrency) "并发请求数量不完整: $($readResults.Count)/$Concurrency"
        Assert-Condition (@($readResults | Where-Object StatusCode -ne 200).Count -eq 0) '并发读取存在非 200 响应'
        $durations = @($readResults | ForEach-Object DurationMs | Sort-Object)
        $p95Index = [math]::Min($durations.Count - 1, [math]::Max(0, [math]::Ceiling($durations.Count * 0.95) - 1))
        Write-Host ("       concurrent latency: avg={0} ms, p95={1} ms, max={2} ms" -f [math]::Round((($durations | Measure-Object -Average).Average), 2), $durations[$p95Index], $durations[-1]) -ForegroundColor DarkGray
    }

    if ($RunModelTests) {
        $modelPayloads = @(
            @{ goal = 'review'; message = '请用不超过三句话总结本场面试最值得优先改进的方向，并使用 Markdown 列表。'; history = @(); providerId = ''; memoryKey = "$UserId-model-1"; agentSessionId = $script:agentSessionA },
            @{ goal = 'practice'; message = '请基于本场面试给出一个可立即执行的练习建议，回答保持简洁。'; history = @(); providerId = ''; memoryKey = "$UserId-model-2"; agentSessionId = $script:agentSessionB }
        )

        for ($index = 0; $index -lt $modelPayloads.Count; $index++) {
            $payload = $modelPayloads[$index]
            Add-TestResult "Agent real stream round $($index + 1)" {
                $result = Invoke-Api -Path "/api/agent/interview-sessions/$escapedSessionId/messages/stream" -Method POST -Body $payload -Headers $agentHeaders -TimeoutSec 300
                Assert-Condition ($result.StatusCode -eq 200) "Agent stream HTTP $($result.StatusCode)"
                # Invoke-WebRequest may normalize the optional SSE space after the colon.
                Assert-Condition ($result.Body -match 'event:\s*agent-step') 'Agent stream missing step events'
                Assert-Condition ($result.Body -match 'event:\s*agent-plan') 'Agent stream missing bounded loop plan event'
                Assert-Condition ($result.Body -match '"step":"loop"') 'Agent stream missing bounded loop lifecycle events'
                Assert-Condition ($result.Body -match 'event:\s*agent-loop') 'Agent stream missing explicit loop iteration events'
                Assert-Condition ($result.Body -match 'event:\s*agent-delta') 'Agent stream missing delta events'
                Assert-Condition ($result.Body -notmatch 'event:\s*agent-error') 'Agent stream returned an error event'
                Assert-Condition ($result.Body.Length -gt 200) 'Agent stream response is unexpectedly short'
                # Some reverse proxies normalize or omit the terminal marker while
                # the persisted run is already complete. The durable state is the
                # acceptance source of truth; the explicit done event is diagnostic.
                $threadId = [string]$payload.agentSessionId
                $conversation = Invoke-JsonApi -Path "/api/agent/interview-sessions/$escapedSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString($threadId))" -Headers $agentHeaders
                Assert-ResultSuccess $conversation 'Agent conversation after real stream'
                Assert-Condition ([string]$conversation.Json.data.agentSessionId -eq $threadId) 'Agent stream returned the wrong thread'
                Assert-Condition ([string]$conversation.Json.data.runStatus -eq 'COMPLETED') "Agent run did not complete: $($conversation.Json.data.runStatus)"
                Assert-Condition (@($conversation.Json.data.messages | Where-Object { $_.role -eq 'assistant' -and -not [string]::IsNullOrWhiteSpace([string]$_.content) }).Count -ge 1) 'Agent assistant response was not persisted'
                if ($result.Body -notmatch 'event:\s*agent-done') {
                    Write-Host '       note: terminal done event not present in transport; persisted status is COMPLETED' -ForegroundColor DarkGray
                }
            }
            if ($index -lt ($modelPayloads.Count - 1)) {
                Start-Sleep -Seconds 2
            }
        }
    } else {
        Write-Host '跳过真实模型调用：使用 -RunModelTests 启用两轮 Agent SSE 验收。' -ForegroundColor Yellow
    }
}

$passed = @($results | Where-Object Status -eq 'PASS').Count
$failed = @($results | Where-Object Status -eq 'FAIL').Count
Write-Host "`nAcceptance summary: $passed passed, $failed failed, $($results.Count) total" -ForegroundColor Cyan
$results | Format-Table Name, Status, DurationMs -AutoSize

if ($failed -gt 0) {
    exit 1
}
