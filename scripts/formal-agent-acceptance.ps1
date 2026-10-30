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
    [switch]$RunModelTests,
    [switch]$RunMemoryManagementTests
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:baseUri = [Uri]$BaseUrl
$script:cookieContainer = New-Object System.Net.CookieContainer
$script:httpHandler = New-Object System.Net.Http.HttpClientHandler
$script:httpHandler.CookieContainer = $script:cookieContainer
$script:httpHandler.UseCookies = $true
$script:httpClient = New-Object System.Net.Http.HttpClient($script:httpHandler)
$script:httpClient.BaseAddress = $script:baseUri
$script:xsrf = ''
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

function Reset-HttpSession {
    if ($null -ne $script:httpClient) { $script:httpClient.Dispose() }
    if ($null -ne $script:httpHandler) { $script:httpHandler.Dispose() }
    $script:cookieContainer = New-Object System.Net.CookieContainer
    $script:httpHandler = New-Object System.Net.Http.HttpClientHandler
    $script:httpHandler.CookieContainer = $script:cookieContainer
    $script:httpHandler.UseCookies = $true
    $script:httpClient = New-Object System.Net.Http.HttpClient($script:httpHandler)
    $script:httpClient.BaseAddress = $script:baseUri
    $script:xsrf = ''
}

function Refresh-XsrfToken {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, '/api/auth/me')
    try {
        $response = $script:httpClient.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Parse-Json $raw 'GET /api/auth/me' | Out-Null
        $cookie = $script:cookieContainer.GetCookies($script:baseUri) |
            Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -Last 1
        if ($null -eq $cookie -or [string]::IsNullOrWhiteSpace($cookie.Value)) {
            throw '无法从 /api/auth/me 获取 XSRF-TOKEN'
        }
        $script:xsrf = $cookie.Value
    } finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Sync-XsrfTokenFromCookie {
    $cookie = $script:cookieContainer.GetCookies($script:baseUri) |
        Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -Last 1
    if ($null -ne $cookie -and -not [string]::IsNullOrWhiteSpace($cookie.Value)) {
        $script:xsrf = $cookie.Value
    }
}

function Invoke-JsonApi(
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    if ($Method -ne 'GET') { Sync-XsrfTokenFromCookie }
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $Path)
    try {
        if ($Method -ne 'GET' -and -not [string]::IsNullOrWhiteSpace($script:xsrf)) {
            $request.Headers.Add('X-XSRF-TOKEN', $script:xsrf)
        }
        if ($null -ne $Body) {
            $jsonBody = $Body | ConvertTo-Json -Depth 50 -Compress
            $request.Content = New-Object System.Net.Http.StringContent($jsonBody, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $script:httpClient.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Sync-XsrfTokenFromCookie
        return Parse-Json $raw "$Method $Path"
    } finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Invoke-Stream([string]$Path, [object]$Body) {
    # SSE 完成后 Spring Security 可能清理旧的 CSRF Cookie；每轮发送前重新预热，
    # 让同一登录会话可以连续运行多个 Agent 会话。
    Refresh-XsrfToken
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $Path)
    try {
        $jsonBody = $Body | ConvertTo-Json -Depth 50 -Compress
        $request.Headers.Add('X-XSRF-TOKEN', $script:xsrf)
        $request.Content = New-Object System.Net.Http.StringContent($jsonBody, [Text.Encoding]::UTF8, 'application/json')
        $response = $script:httpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "formal-agent-$suffix@example.com"
        $registerBody = @{ email = $Email; password = $Password; nickname = 'Formal Agent Acceptance' }
        $registerJson = Invoke-JsonApi '/api/auth/register' 'POST' $registerBody
        Assert-Pass ($registerJson.code -eq 200) '注册验收账号成功'
        Reset-HttpSession
    }

    $loginJson = Invoke-JsonApi '/api/auth/login' 'POST' @{ email = $Email; password = $Password }
    $loginCode = 0
    $loginCodeParsed = [int]::TryParse([string]$loginJson.code, [ref]$loginCode)
    Assert-Pass ($loginCodeParsed -and $loginCode -eq 200 -and $loginJson.success -eq $true) '正式登录成功'

    Refresh-XsrfToken

    $list = Invoke-JsonApi '/api/interview/sessions'
    Assert-Pass ($list.code -eq 200) '读取当前账号面试列表成功'
    $sessions = @($list.data)
    if ([string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        if ($CreateInterview) {
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
        } elseif ($sessions.Count -eq 0) {
            throw '当前账号没有面试会话；请传入 -CreateInterview 或 -InterviewSessionId'
        } else {
            $InterviewSessionId = [string]$sessions[0].sessionId
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
            $eventSummary = (($stream -split "`n" |
                Where-Object { $_ -match '^event:' } |
                ForEach-Object { $_ -replace '^event:\s*', '' } |
                Select-Object -Unique) -join ',').Trim()
            Assert-Pass ($stream -match 'event:\s*agent-plan') "Agent 第 $($i + 1) 轮包含执行计划（事件=$eventSummary）"
            Assert-Pass ($stream -match 'event:\s*agent-loop') "Agent 第 $($i + 1) 轮包含 Loop 事件（事件=$eventSummary）"
            Assert-Pass ($stream -match 'event:\s*agent-delta') "Agent 第 $($i + 1) 轮包含流式输出（事件=$eventSummary）"
            Assert-Pass ($stream -match 'event:\s*agent-done' -and $stream -notmatch 'event:\s*agent-error') "Agent 第 $($i + 1) 轮正常完成（事件=$eventSummary）"
            $conversation = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversation?agentSessionId=$([Uri]::EscapeDataString([string]$payloads[$i].agentSessionId))"
            Assert-Pass ($conversation.code -eq 200 -and $conversation.data.runStatus -eq 'COMPLETED') "Agent 第 $($i + 1) 轮状态持久化为 COMPLETED"
            $usage = $conversation.data.runUsage
            $usageText = if ($null -eq $usage) { 'null' } else { $usage | ConvertTo-Json -Compress }
            Assert-Pass ($null -ne $usage -and [int]$usage.modelCalls -ge 1 -and [int64]$usage.durationMillis -ge 0) "Agent 第 $($i + 1) 轮用量摘要已持久化（实际=$usageText）"
        }

    } else {
        Write-Host '跳过真实模型调用：使用 -RunModelTests 启用两轮 Agent SSE 验收。' -ForegroundColor Yellow
    }

    $memory = Invoke-JsonApi '/api/agent/memory'
    Assert-Pass ($memory.code -eq 200 -and $null -ne $memory.data) '正式登录可读取 Agent 长期记忆摘要'
    Assert-Pass ($memory.data.PSObject.Properties.Name -notcontains 'memoryKey') '长期记忆接口不暴露内部 memoryKey'
    if ($RunMemoryManagementTests) {
        $memoryItems = @($memory.data.items)
        Assert-Pass ($memoryItems.Count -gt 0) '长期记忆存在可管理的结构化记忆项'
        $item = $memoryItems[0]
        Assert-Pass ($null -ne $item.expiresAt -and [string]$item.expiresAt -ne '') '长期记忆包含过期时间'
        $editedContent = "$($item.content)（验收编辑）"
        $updated = Invoke-JsonApi "/api/agent/memory/items/$([Uri]::EscapeDataString([string]$item.id))" 'PATCH' @{
            content = $editedContent
            jobScope = 'java-backend'
        }
        $updatedItem = @($updated.data.items) | Where-Object { $_.id -eq $item.id } | Select-Object -First 1
        Assert-Pass ($updated.code -eq 200 -and $null -ne $updatedItem -and $updatedItem.content -eq $editedContent -and $updatedItem.jobScope -eq 'java-backend') `
            '长期记忆单项编辑和岗位范围保存成功'

        $deleted = Invoke-JsonApi "/api/agent/memory/items/$([Uri]::EscapeDataString([string]$item.id))" 'DELETE'
        $deletedItem = @($deleted.data.items) | Where-Object { $_.id -eq $item.id }
        Assert-Pass ($deleted.code -eq 200 -and $null -eq $deletedItem) '长期记忆单项删除成功'
    }

    Write-Host "Formal Agent acceptance: PASS (interview=$InterviewSessionId)" -ForegroundColor Cyan
} finally {
    if ($createdInterview -and -not [string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        try { Invoke-JsonApi "/api/interview/sessions/$([Uri]::EscapeDataString($InterviewSessionId))" 'DELETE' | Out-Null } catch { }
    }
    if ($null -ne $script:httpClient) { $script:httpClient.Dispose() }
    if ($null -ne $script:httpHandler) { $script:httpHandler.Dispose() }
}
