<#
.SYNOPSIS
    采集正式 Agent 运行样本，生成 agentEvaluation 可消费的 JSONL。

.DESCRIPTION
    使用正式登录、真实 Agent SSE 和会话持久化接口，逐条执行测试语料，提取回答、
    来源、Loop 观察、Provider、模型和用量摘要。脚本只创建临时验收面试和线程；
    线程会在每个样本完成后归档，创建的面试会在脚本结束时删除。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$Email = '',
    [string]$Password = 'StrongPass123',
    [string]$InterviewSessionId = '',
    [string]$DatasetPath = 'app/src/test/resources/agent-eval/dataset-v1.jsonl',
    [string]$OutputPath = 'build/agent-eval/real-run.jsonl',
    [int]$MaxSamples = 0,
    [string]$ProviderId = 'pixel',
    [switch]$CreateInterview,
    [switch]$RunQualityGate
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path $env:TEMP "agent-quality-$([guid]::NewGuid()).cookie"
$createdInterview = $false
$createdThreads = [System.Collections.Generic.List[string]]::new()

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
    $args = @('-sS', '--max-time', '60', '-b', $jar, '-X', $Method, "$BaseUrl$Path")
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
    throw "$Method $Path 连续请求失败：$lastResponse"
}

function Parse-SseEvents([string]$Raw) {
    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($block in ($Raw -split "\r?\n\r?\n")) {
        if ([string]::IsNullOrWhiteSpace($block)) { continue }
        $eventName = (($block -split "\r?\n") |
            Where-Object { $_ -like 'event:*' } |
            Select-Object -First 1)
        $eventName = if ($eventName) { $eventName.Substring(6).Trim() } else { '' }
        $dataLines = @($block -split "\r?\n" |
            Where-Object { $_ -like 'data:*' } |
            ForEach-Object { $_.Substring(5).TrimStart() })
        if ($dataLines.Count -eq 0) { continue }
        try {
            $data = ($dataLines -join "`n") | ConvertFrom-Json -Depth 50
            $events.Add([pscustomobject]@{ name = $eventName; data = $data })
        } catch {
            Write-Warning "跳过无法解析的 SSE 事件：$($_.Exception.Message)"
        }
    }
    return $events
}

function Invoke-AgentStream([string]$SessionId, [object]$Body) {
    $output = Join-Path $env:TEMP "agent-quality-stream-$([guid]::NewGuid()).txt"
    try {
        $args = @(
            '-sS', '--max-time', '300', '-b', $jar, '-X', 'POST',
            "$BaseUrl/api/agent/interview-sessions/$SessionId/messages/stream",
            '-H', "X-XSRF-TOKEN:$script:xsrf", '-H', 'Content-Type: application/json',
            '--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress), '-o', $output
        )
        & curl.exe @args 2>$null | Out-Null
        $raw = if (Test-Path -LiteralPath $output) {
            Get-Content -LiteralPath $output -Raw
        } else { '' }
        if ([string]::IsNullOrWhiteSpace($raw)) {
            throw 'Agent SSE 返回为空'
        }
        return $raw
    } finally {
        Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Conversation([string]$SessionId, [string]$AgentSessionId) {
    $query = "?agentSessionId=$([Uri]::EscapeDataString($AgentSessionId))"
    $last = $null
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $last = Invoke-JsonApi "/api/agent/interview-sessions/$SessionId/conversation$query"
        $status = [string]$last.data.runStatus
        if ($status -eq 'COMPLETED' -or $status -eq 'FAILED' -or $status -eq 'CANCELLED') {
            return $last
        }
        if ($status -eq 'IDLE' -and $attempt -eq 1) {
            throw "Agent 请求没有进入 RUNNING 状态（可能是认证、参数或服务错误）"
        }
        Start-Sleep -Seconds 5
    }
    throw "Agent 运行超过等待窗口：$($last.data.runStatus)"
}

function Get-LoopIterations($Events) {
    $byIteration = @{}
    foreach ($event in $Events | Where-Object { $_.data.type -eq 'loop' }) {
        $step = [string]$event.data.step
        if ($step -notmatch '^loop-(\d+)$') { continue }
        $iteration = [int]$Matches[1]
        if (-not $byIteration.ContainsKey($iteration)) {
            $byIteration[$iteration] = [ordered]@{
                iteration = $iteration
                action = ''
                observation = ''
            }
        }
        if ([string]$event.data.status -eq 'running') {
            $byIteration[$iteration].action = [string]$event.data.message
        } else {
            $byIteration[$iteration].observation = [string]$event.data.message
        }
    }
    return @($byIteration.Values |
        Sort-Object { [int]$_.iteration } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_.observation) })
}

function Get-ConversationAnswer($Conversation) {
    $assistant = @($Conversation.data.messages |
        Where-Object { [string]$_.role -eq 'assistant' } | Select-Object -Last 1)
    if ($assistant.Count -eq 0) { return '' }
    return [string]$assistant[0].content
}

function Get-RunSources($Conversation) {
    return @($Conversation.data.runSources | ForEach-Object {
        [ordered]@{
            kind = [string]$_.kind
            title = [string]$_.title
            score = if ($null -eq $_.score) { $null } else { [double]$_.score }
            reference = [string]$_.reference
        }
    })
}

function New-Interview {
    $requestId = "agent-quality-$([guid]::NewGuid().ToString('N'))"
    $created = Invoke-JsonApi '/api/interview/sessions' 'POST' @{
        resumeText = ''
        questionCount = 3
        forceCreate = $true
        skillId = 'java-backend'
        difficulty = 'mid'
        requestId = $requestId
    }
    Assert-Pass ($created.code -eq 200 -and $null -ne $created.data.sessionId) '创建质量评测临时面试成功'
    $script:createdInterview = $true
    return [string]$created.data.sessionId
}

function Get-CodeVersion {
    try {
        $version = (& git rev-parse --short HEAD 2>$null).Trim()
        if ($version) { return $version }
        return 'unknown'
    } catch { return 'unknown' }
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "agent-quality-$suffix@example.com"
        $register = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
            -H 'Content-Type: application/json' `
            --data-raw (@{ email = $Email; password = $Password; nickname = 'Agent Quality Acceptance' } | ConvertTo-Json -Compress)
        $registerJson = Parse-Json ($register -join [Environment]::NewLine) '注册质量评测账号'
        Assert-Pass ($registerJson.code -eq 200) '注册质量评测账号成功'
    }

    $login = & curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d (@{ email = $Email; password = $Password } | ConvertTo-Json -Compress) `
        "$BaseUrl/api/auth/login"
    $loginJson = Parse-Json ($login -join [Environment]::NewLine) '登录质量评测账号'
    Assert-Pass ($loginJson.code -eq 200) '登录质量评测账号成功'
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrf = $xsrfParts[$xsrfParts.Length - 1]
    $script:xsrf = $xsrf

    if ([string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        if (-not $CreateInterview) {
            throw '没有面试会话；请传入 -InterviewSessionId 或 -CreateInterview'
        }
        $InterviewSessionId = New-Interview
    }

    if (-not (Test-Path -LiteralPath $DatasetPath)) {
        throw "找不到评测语料：$DatasetPath"
    }
    $samples = @(Get-Content -LiteralPath $DatasetPath -Encoding utf8 |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json -Depth 50 })
    if ($MaxSamples -gt 0) { $samples = @($samples | Select-Object -First $MaxSamples) }
    if ($samples.Count -eq 0) { throw '评测语料为空' }

    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if (Test-Path -LiteralPath $OutputPath) {
        Remove-Item -LiteralPath $OutputPath -Force
    }
    $codeVersion = Get-CodeVersion
    $selectedProviderId = $ProviderId.Trim()
    $sampleIndex = 0
    foreach ($sample in $samples) {
        $sampleIndex++
        $sampleId = [string]$sample.id
        $thread = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations" 'POST' @{
            title = "质量评测 $sampleId"
        }
        Assert-Pass ($thread.code -eq 200 -and $null -ne $thread.data.agentSessionId) "创建评测线程成功（$sampleId）"
        $agentSessionId = [string]$thread.data.agentSessionId
        $createdThreads.Add($agentSessionId)

        $payload = @{
            goal = [string]$sample.goal
            message = [string]$sample.message
            history = @()
            providerId = $selectedProviderId
            memoryKey = "aq-$([guid]::NewGuid().ToString('N').Substring(0, 16))"
            agentSessionId = $agentSessionId
        }
        Write-Host "[$sampleIndex/$($samples.Count)] 运行真实样本：$sampleId" -ForegroundColor Cyan
        $stream = Invoke-AgentStream $InterviewSessionId $payload
        $events = @(Parse-SseEvents $stream)
        $errors = @($events | Where-Object { $_.data.type -eq 'error' })
        if ($errors.Count -gt 0) {
            throw "真实样本 SSE 返回错误：$([string]$errors[0].data.message)"
        }
        $conversation = Wait-Conversation $InterviewSessionId $agentSessionId
        Assert-Pass ($conversation.code -eq 200 -and $conversation.data.runStatus -eq 'COMPLETED') `
            "真实样本完成（$sampleId）"

        $usage = $conversation.data.runUsage
        $record = [ordered]@{
            sampleId = $sampleId
            provider = if ($usage.provider) { [string]$usage.provider } else { 'unknown' }
            model = if ($usage.model) { [string]$usage.model } else { 'unknown' }
            promptVersion = 'agent-coach-v1'
            codeVersion = $codeVersion
            answer = Get-ConversationAnswer $conversation
            promptTokens = if ($usage) { [int]$usage.promptTokens } else { 0 }
            completionTokens = if ($usage) { [int]$usage.completionTokens } else { 0 }
            cost = if ($usage -and $null -ne $usage.cost) { [double]$usage.cost } else { $null }
            sources = @(Get-RunSources $conversation)
            loopIterations = @(Get-LoopIterations $events)
        }
        $record | ConvertTo-Json -Depth 30 -Compress |
            Add-Content -LiteralPath $OutputPath -Encoding utf8
        Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId" 'DELETE' | Out-Null
    }

    Write-Host "[PASS] 已生成真实评测 JSONL：$OutputPath" -ForegroundColor Green
    if ($RunQualityGate) {
        & .\gradlew.bat :app:agentEvaluation `
            "-PagentEvalInput=$OutputPath" `
            "-PagentEvalReportDir=build/reports/agent-eval" `
            "-PagentEvalRunId=real-$codeVersion" `
            --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Agent 质量评测门禁失败' }
    }
} finally {
    foreach ($agentSessionId in $createdThreads) {
        try {
            Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId" 'DELETE' | Out-Null
        } catch { }
    }
    if ($createdInterview -and -not [string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        try { Invoke-JsonApi "/api/interview/sessions/$InterviewSessionId" 'DELETE' | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $jar -Force -ErrorAction SilentlyContinue
}
