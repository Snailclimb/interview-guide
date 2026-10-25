<#!
.SYNOPSIS
    准备一次 Agent 应用重启恢复验收。

.DESCRIPTION
    创建临时正式登录账号、面试和 Agent 线程，然后在后台发起 SSE 请求。
    脚本输出 state.json 路径；停止并重新启动 API 容器后，可使用
    -VerifyStatePath 检查恢复结果和消息数量。

    该脚本不负责执行远程容器重启，避免把部署权限写入验收脚本。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$StatePath = '',
    [string]$VerifyStatePath = '',
    # 启动恢复后还要经历上下文构建和一次模型生成，远程真实模型可能超过 1 分钟。
    [int]$RecoveryWaitSeconds = 180,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 50 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Invoke-JsonApi(
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $args = @('-sS', '-b', $script:jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $script:xsrf")
        $args += @('-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $lastResponse = ''
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $raw = & curl.exe @args 2>$null
        $lastResponse = ($raw -join [Environment]::NewLine)
        $transient = $LASTEXITCODE -ne 0 -or $lastResponse -match '<h1>50[234] Bad Gateway|<h1>503 Service Unavailable'
        if (-not $transient) {
            return Parse-Json $lastResponse "$Method $Path"
        }
        if ($attempt -lt 8) { Start-Sleep -Seconds 3 }
    }
    throw "$Method $Path 在应用启动窗口内连续失败：$lastResponse"
}

function New-State {
    $id = [guid]::NewGuid().ToString('N')
    $dir = Join-Path $env:TEMP "agent-recovery-$id"
    New-Item -ItemType Directory -Path $dir | Out-Null
    $jar = Join-Path $dir 'cookie.txt'
    $payloadFile = Join-Path $dir 'payload.json'
    $stream = Join-Path $dir 'stream.txt'
    $streamError = Join-Path $dir 'stream.err'
    $email = "recovery-$($id.Substring(0, 12))@example.com"
    $password = 'StrongPass123'

    $register = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
        -H 'Content-Type: application/json' `
        --data-raw (@{ email = $email; password = $password; nickname = 'Recovery Acceptance' } | ConvertTo-Json -Compress)
    $registerJson = Parse-Json ($register -join [Environment]::NewLine) '注册验收账号'
    Assert-Pass ($registerJson.code -eq 200) '注册重启恢复验收账号成功'

    $login = & curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d (@{ email = $email; password = $password } | ConvertTo-Json -Compress) `
        "$BaseUrl/api/auth/login"
    $loginJson = Parse-Json ($login -join [Environment]::NewLine) '登录验收账号'
    Assert-Pass ($loginJson.code -eq 200) '登录重启恢复验收账号成功'
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrf = $xsrfParts[$xsrfParts.Length - 1]
    $script:jar = $jar
    $script:xsrf = $xsrf

    $requestId = "agent-recovery-$id"
    $created = Invoke-JsonApi '/api/interview/sessions' 'POST' @{
        resumeText = ''
        questionCount = 3
        forceCreate = $true
        skillId = 'java-backend'
        difficulty = 'mid'
        requestId = $requestId
    }
    Assert-Pass ($created.code -eq 200 -and $null -ne $created.data.sessionId) `
        "创建重启恢复验收面试成功（code=$($created.code), message=$($created.message)）"
    $sessionId = [string]$created.data.sessionId

    $thread = Invoke-JsonApi "/api/agent/interview-sessions/$sessionId/conversations" 'POST' @{
        title = '应用重启恢复验收线程'
    }
    Assert-Pass ($thread.code -eq 200 -and $null -ne $thread.data.agentSessionId) '创建重启恢复验收线程成功'
    $agentSessionId = [string]$thread.data.agentSessionId

    $payload = @{
        goal = 'review'
        message = '请进行一份完整的面试复盘，详细说明优先级、证据和可执行改进步骤，回答尽量完整。'
        history = @()
        providerId = ''
        memoryKey = "recovery-$id"
        agentSessionId = $agentSessionId
    } | ConvertTo-Json -Depth 20 -Compress
    $payload | Set-Content -LiteralPath $payloadFile -Encoding utf8
    $curlArgs = @(
        '-sS', '--max-time', '300', '-b', $jar, '-X', 'POST',
        "$BaseUrl/api/agent/interview-sessions/$sessionId/messages/stream",
        '-H', "X-XSRF-TOKEN:$xsrf", '-H', 'Content-Type:application/json',
        '--data-binary', "@$payloadFile", '-o', $stream
    )
    $process = Start-Process -FilePath 'curl.exe' -ArgumentList $curlArgs `
        -WindowStyle Hidden -RedirectStandardError $streamError -PassThru

    $script:state = [ordered]@{
        dir = $dir
        jar = $jar
        payloadFile = $payloadFile
        stream = $stream
        streamError = $streamError
        email = $email
        password = $password
        xsrf = $xsrf
        sessionId = $sessionId
        agentSessionId = $agentSessionId
        curlPid = $process.Id
        createdAt = (Get-Date).ToString('o')
    }
    $script:state | ConvertTo-Json | Set-Content -LiteralPath $StatePath -Encoding utf8
    Write-Host "[PASS] Agent 流式请求已启动，curl PID=$($process.Id)" -ForegroundColor Green
    Write-Host "STATE_PATH=$StatePath" -ForegroundColor Cyan
    Write-Host '现在停止 API 容器，等待超过 recovery-min-age-seconds 后再启动。' -ForegroundColor Yellow
}

function Read-State([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "找不到状态文件：$Path" }
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 50
}

function Verify-State([string]$Path) {
    $state = Read-State $Path
    $script:jar = $state.jar
    $script:xsrf = $state.xsrf
    if ($RecoveryWaitSeconds -gt 0) {
        Write-Host "等待恢复器完成（最多等待 $RecoveryWaitSeconds 秒）..." -ForegroundColor Yellow
        $deadline = (Get-Date).AddSeconds($RecoveryWaitSeconds)
        do {
            $conversation = Invoke-JsonApi "/api/agent/interview-sessions/$($state.sessionId)/conversation?agentSessionId=$([Uri]::EscapeDataString([string]$state.agentSessionId))"
            $data = $conversation.data
            if ($data.runStatus -eq 'COMPLETED' -or $data.runStatus -eq 'FAILED') { break }
            Start-Sleep -Seconds 5
        } while ((Get-Date) -lt $deadline)
    } else {
        $conversation = Invoke-JsonApi "/api/agent/interview-sessions/$($state.sessionId)/conversation?agentSessionId=$([Uri]::EscapeDataString([string]$state.agentSessionId))"
        $data = $conversation.data
    }
    $conversation = Invoke-JsonApi "/api/agent/interview-sessions/$($state.sessionId)/conversation?agentSessionId=$([Uri]::EscapeDataString([string]$state.agentSessionId))"
    $data = $conversation.data
    Assert-Pass ($conversation.code -eq 200) '重启后可读取 Agent 会话'
    Assert-Pass ($data.runStatus -eq 'COMPLETED') "恢复任务最终完成（status=$($data.runStatus)）"
    $messages = @($data.messages)
    $userMessages = @($messages | Where-Object { $_.role -eq 'USER' })
    $assistantMessages = @($messages | Where-Object { $_.role -eq 'ASSISTANT' })
    Assert-Pass ($userMessages.Count -eq 1) "恢复后用户消息没有重复（count=$($userMessages.Count)）"
    Assert-Pass ($assistantMessages.Count -eq 1) "恢复后助手消息没有重复（count=$($assistantMessages.Count)）"
    Assert-Pass (-not [string]::IsNullOrWhiteSpace([string]$assistantMessages[0].content)) '恢复后助手回答已持久化'
    Write-Host "Agent restart recovery acceptance: PASS (session=$($state.sessionId))" -ForegroundColor Cyan
}

function Remove-StateData([string]$Path) {
    $state = Read-State $Path
    $script:jar = $state.jar
    $script:xsrf = $state.xsrf
    try {
        Invoke-JsonApi "/api/interview/sessions/$($state.sessionId)" 'DELETE' | Out-Null
        Write-Host '[PASS] 已删除重启恢复验收面试' -ForegroundColor Green
    } finally {
        if ($state.dir -and (Test-Path -LiteralPath $state.dir)) {
            Remove-Item -LiteralPath $state.dir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

if ([string]::IsNullOrWhiteSpace($StatePath)) {
    $StatePath = Join-Path $env:TEMP "agent-recovery-state-$([guid]::NewGuid().ToString('N')).json"
}
if (-not [string]::IsNullOrWhiteSpace($VerifyStatePath)) {
    Verify-State $VerifyStatePath
    if ($Cleanup) { Remove-StateData $VerifyStatePath }
} elseif ($Cleanup) {
    throw '-Cleanup 必须与 -VerifyStatePath 一起使用'
} else {
    New-State
}
