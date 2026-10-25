<#!
.SYNOPSIS
    正式登录模式下的 Agent 会话、SSE 和真实模型验收。

.DESCRIPTION
    默认只检查已有账号下的面试和 Agent 会话；-CreateInterview 会创建一轮
    真实面试题，-RunModelTests 会实际调用两轮 Agent 模型。脚本使用服务端
    登录 Cookie，不再使用迁移期的 X-User-Id。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$Email = '',
    [string]$Password = 'StrongPass123',
    [string]$InterviewSessionId = '',
    [switch]$CreateInterview,
    [switch]$RunModelTests
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path $env:TEMP "formal-agent-$([guid]::NewGuid()).cookie"
$createdInterview = $false

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 50 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Invoke-JsonApi(
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $args = @('-sS', '-b', $jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $script:xsrf")
        $args += @('-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $raw = & curl.exe @args
    Parse-Json ($raw -join [Environment]::NewLine) "$Method $Path"
}

function Invoke-Stream([string]$Path, [object]$Body) {
    $output = Join-Path $env:TEMP "formal-agent-stream-$([guid]::NewGuid()).txt"
    try {
        $args = @(
            '-sS', '--max-time', '300', '-b', $jar, '-X', 'POST', "$BaseUrl$Path",
            '-H', "X-XSRF-TOKEN: $script:xsrf",
            '-H', 'Content-Type: application/json',
            '--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress),
            '-o', $output
        )
        & curl.exe @args | Out-Null
        return Get-Content -LiteralPath $output -Raw
    } finally {
        Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "formal-agent-$suffix@example.com"
        $registerBody = @{ email = $Email; password = $Password; nickname = 'Formal Agent Acceptance' }
        $register = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
            -H 'Content-Type: application/json' --data-raw ($registerBody | ConvertTo-Json -Compress)
        $registerJson = Parse-Json ($register -join [Environment]::NewLine) '注册验收账号'
        Assert-Pass ($registerJson.code -eq 200) '注册验收账号成功'
    }

    $loginBody = @{ email = $Email; password = $Password } | ConvertTo-Json -Compress
    $login = & curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d $loginBody "$BaseUrl/api/auth/login"
    $loginJson = Parse-Json ($login -join [Environment]::NewLine) '登录验收账号'
    Assert-Pass ($loginJson.code -eq 200) '正式登录成功'

    # 登录响应已经下发 CSRF Cookie；不要让后续 GET 的空 Set-Cookie 覆盖它。
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '正式登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrf = $xsrfParts[$xsrfParts.Length - 1]

    $list = Invoke-JsonApi '/api/interview/sessions'
    Assert-Pass ($list.code -eq 200) '读取当前账号面试列表成功'
    $sessions = @($list.data)
    if ([string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        if ($sessions.Count -eq 0 -and -not $CreateInterview) {
            throw '当前账号没有面试会话；请传入 -CreateInterview 或 -InterviewSessionId'
        }
        if ($sessions.Count -gt 0) {
            $InterviewSessionId = [string]$sessions[0].sessionId
        } else {
            $requestId = "formal-agent-$([guid]::NewGuid().ToString('N'))"
            $created = Invoke-JsonApi '/api/interview/sessions' 'POST' @{
                resumeText = ''
                questionCount = 3
                forceCreate = $true
                skillId = 'java-backend'
                difficulty = 'mid'
                requestId = $requestId
            }
            Assert-Pass ($created.code -eq 200 -and $null -ne $created.data.sessionId) '创建真实面试会话成功'
            $InterviewSessionId = [string]$created.data.sessionId
            $createdInterview = $true
        }
    }

    $session = Invoke-JsonApi "/api/interview/sessions/$([Uri]::EscapeDataString($InterviewSessionId))"
    Assert-Pass ($session.code -eq 200) '正式登录可读取目标面试会话'

    $threads = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations"
    Assert-Pass ($threads.code -eq 200) '读取 Agent 会话列表成功'
    $threadItems = @($threads.data)
    while ($threadItems.Count -lt 2) {
        $createdThread = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations" 'POST' @{
            title = "正式登录 Agent 验收线程 $($threadItems.Count + 1)"
        }
        Assert-Pass ($createdThread.code -eq 200 -and $null -ne $createdThread.data.agentSessionId) '创建独立 Agent 会话成功'
        $threadItems += $createdThread.data
    }
    $threadA = [string]$threadItems[0].agentSessionId
    $threadB = [string]$threadItems[1].agentSessionId
    Assert-Pass ($threadA -ne $threadB) '两个 Agent 会话 ID 独立'

    if ($RunModelTests) {
        $payloads = @(
            @{ goal = 'review'; message = '请用三句话总结本场面试最值得优先改进的方向，并使用 Markdown 列表。'; history = @(); providerId = ''; memoryKey = "formal-agent-$Email-1"; agentSessionId = $threadA },
            @{ goal = 'practice'; message = '请给出一个可以立即执行的练习建议，回答保持简洁。'; history = @(); providerId = ''; memoryKey = "formal-agent-$Email-2"; agentSessionId = $threadB }
        )
        for ($i = 0; $i -lt $payloads.Count; $i++) {
            $stream = Invoke-Stream "/api/agent/interview-sessions/$InterviewSessionId/messages/stream" $payloads[$i]
            Assert-Pass ($stream -match 'event:\s*agent-plan') "Agent 第 $($i + 1) 轮包含执行计划"
            Assert-Pass ($stream -match 'event:\s*agent-loop') "Agent 第 $($i + 1) 轮包含 Loop 事件"
            Assert-Pass ($stream -match 'event:\s*agent-delta') "Agent 第 $($i + 1) 轮包含流式输出"
            Assert-Pass ($stream -match 'event:\s*agent-done' -and $stream -notmatch 'event:\s*agent-error') "Agent 第 $($i + 1) 轮正常完成"
            $conversation = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString([string]$payloads[$i].agentSessionId))"
            Assert-Pass ($conversation.code -eq 200 -and $conversation.data.runStatus -eq 'COMPLETED') "Agent 第 $($i + 1) 轮状态持久化为 COMPLETED"
            $usage = $conversation.data.runUsage
            $usageText = if ($null -eq $usage) { 'null' } else { $usage | ConvertTo-Json -Compress }
            Assert-Pass ($null -ne $usage -and [int]$usage.modelCalls -ge 1 -and [int64]$usage.durationMillis -ge 0) "Agent 第 $($i + 1) 轮用量摘要已持久化（实际=$usageText）"
        }

        $memory = Invoke-JsonApi '/api/agent/memory'
        Assert-Pass ($memory.code -eq 200 -and $null -ne $memory.data) '正式登录可读取 Agent 长期记忆摘要'
        Assert-Pass ($memory.data.PSObject.Properties.Name -notcontains 'memoryKey') '长期记忆接口不暴露内部 memoryKey'
    } else {
        Write-Host '跳过真实模型调用：使用 -RunModelTests 启用两轮 Agent SSE 验收。' -ForegroundColor Yellow
    }

    Write-Host "Formal Agent acceptance: PASS (interview=$InterviewSessionId)" -ForegroundColor Cyan
} finally {
    if ($createdInterview -and -not [string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        try { Invoke-JsonApi "/api/interview/sessions/$([Uri]::EscapeDataString($InterviewSessionId))" 'DELETE' | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $jar -Force -ErrorAction SilentlyContinue
}
