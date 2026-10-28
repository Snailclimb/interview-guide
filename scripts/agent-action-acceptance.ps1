<#!
.SYNOPSIS
    正式登录模式下的 Agent 受控动作真实接口验收。

.DESCRIPTION
    验证训练任务动作从草稿、人工编辑、确认到任务状态推进的完整边界，
    同时验证幂等键、结束状态保护和跨账号访问拒绝。脚本只创建临时账号
    和面试数据；不调用 Agent 模型，不产生额外模型费用。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$Email = '',
    [string]$Password = 'StrongPass123',
    [string]$InterviewSessionId = ''
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$createdInterview = $false
$primaryJar = Join-Path $env:TEMP "agent-action-$([guid]::NewGuid()).cookie"
$secondaryJar = Join-Path $env:TEMP "agent-action-secondary-$([guid]::NewGuid()).cookie"

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 50 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Invoke-Api(
    [string]$Jar,
    [string]$Xsrf,
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $args = @('-sS', '-b', $Jar, '-c', $Jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $Xsrf", '-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $raw = & curl.exe @args
    Parse-Json ($raw -join [Environment]::NewLine) "$Method $Path"
}

function Login([string]$EmailAddress, [string]$Jar) {
    $body = @{ email = $EmailAddress; password = $Password } | ConvertTo-Json -Compress
    $login = & curl.exe -sS -c $Jar -H 'Content-Type: application/json' -d $body `
        "$BaseUrl/api/auth/login"
    $json = Parse-Json ($login -join [Environment]::NewLine) "登录 $EmailAddress"
    Assert-Pass ($json.code -eq 200) "正式登录 $EmailAddress"
    $xsrfLine = Get-Content -LiteralPath $Jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw "登录 $EmailAddress 未下发 XSRF-TOKEN" }
    $parts = $xsrfLine -split '\s+'
    return [string]$parts[$parts.Length - 1]
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "agent-action-$suffix@example.com"
        $registerBody = @{
            email = $Email
            password = $Password
            nickname = 'Agent Action Acceptance'
        } | ConvertTo-Json -Compress
        $register = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
            -H 'Content-Type: application/json' --data-raw $registerBody
        $registerJson = Parse-Json ($register -join [Environment]::NewLine) '注册动作验收账号'
        Assert-Pass ($registerJson.code -eq 200) '注册动作验收账号'
    }

    $xsrf = Login $Email $primaryJar
    if ([string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        $requestId = "agent-action-$([guid]::NewGuid().ToString('N'))"
        $created = Invoke-Api $primaryJar $xsrf '/api/interview/sessions' 'POST' @{
            resumeText = ''
            questionCount = 3
            forceCreate = $true
            skillId = 'java-backend'
            difficulty = 'mid'
            requestId = $requestId
        }
        Assert-Pass ($created.code -eq 200 -and $null -ne $created.data.sessionId) '创建动作验收面试'
        $InterviewSessionId = [string]$created.data.sessionId
        $createdInterview = $true
    }

    $thread = Invoke-Api $primaryJar $xsrf "/api/agent/interview-sessions/$InterviewSessionId/conversations" 'POST' @{
        title = 'Agent 受控动作验收线程'
    }
    Assert-Pass ($thread.code -eq 200 -and $null -ne $thread.data.agentSessionId) '创建动作验收 Agent 线程'
    $agentSessionId = [string]$thread.data.agentSessionId

    $idempotencyKey = "acceptance-training-task-$([guid]::NewGuid().ToString('N'))"
    $draftBody = @{
        content = "# 动作验收训练任务`n- 复习 Redis 缓存击穿`n- 完成一道幂等性练习题"
        idempotencyKey = $idempotencyKey
    }
    $draft = Invoke-Api $primaryJar $xsrf `
        "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId/actions/training-task-draft" `
        'POST' $draftBody
    Assert-Pass ($draft.code -eq 200 -and $draft.data.status -eq 'PROPOSED') '生成待确认训练任务草稿'
    $actionId = [string]$draft.data.actionId

    $sameDraft = Invoke-Api $primaryJar $xsrf `
        "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId/actions/training-task-draft" `
        'POST' $draftBody
    Assert-Pass ($sameDraft.code -eq 200 -and $sameDraft.data.actionId -eq $actionId) '幂等键不会生成重复动作'

    $editedContent = "# 动作验收训练任务（已编辑）`n- 复习 Redis 缓存击穿`n- 补充幂等重试边界"
    $edited = Invoke-Api $primaryJar $xsrf "/api/agent/actions/$actionId" 'PATCH' @{ content = $editedContent }
    Assert-Pass ($edited.code -eq 200 -and $edited.data.status -eq 'PROPOSED' -and $edited.data.draftContent -eq $editedContent) `
        '待确认草稿可以人工编辑'

    $beforeConfirm = Invoke-Api $primaryJar $xsrf '/api/agent/training-tasks'
    Assert-Pass ($beforeConfirm.code -eq 200 -and @($beforeConfirm.data | Where-Object sourceActionId -eq $actionId).Count -eq 0) `
        '未确认动作不会创建训练任务'

    $confirmed = Invoke-Api $primaryJar $xsrf "/api/agent/actions/$actionId/confirm" 'POST'
    Assert-Pass ($confirmed.code -eq 200 -and $confirmed.data.status -eq 'SUCCEEDED') '确认动作并创建训练任务'

    $tasks = Invoke-Api $primaryJar $xsrf '/api/agent/training-tasks'
    $task = @($tasks.data | Where-Object sourceActionId -eq $actionId | Select-Object -First 1)
    Assert-Pass ($tasks.code -eq 200 -and $task.Count -eq 1 -and $task[0].status -eq 'TODO') '确认后生成待开始训练任务'
    $taskId = [string]$task[0].taskId

    $started = Invoke-Api $primaryJar $xsrf "/api/agent/training-tasks/$taskId" 'PATCH' @{ status = 'IN_PROGRESS' }
    Assert-Pass ($started.code -eq 200 -and $started.data.status -eq 'IN_PROGRESS') '训练任务可以推进到进行中'
    $completed = Invoke-Api $primaryJar $xsrf "/api/agent/training-tasks/$taskId" 'PATCH' @{ status = 'COMPLETED' }
    Assert-Pass ($completed.code -eq 200 -and $completed.data.status -eq 'COMPLETED') '训练任务可以完成'
    $reopen = Invoke-Api $primaryJar $xsrf "/api/agent/training-tasks/$taskId" 'PATCH' @{ status = 'TODO' }
    Assert-Pass ($reopen.code -ne 200) '已完成训练任务不能回退'

    $secondarySuffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $secondaryEmail = "agent-action-other-$secondarySuffix@example.com"
    $secondaryRegisterBody = @{ email = $secondaryEmail; password = $Password; nickname = 'Other User' } |
        ConvertTo-Json -Compress
    $secondaryRegister = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
        -H 'Content-Type: application/json' --data-raw $secondaryRegisterBody
    $secondaryRegisterJson = Parse-Json ($secondaryRegister -join [Environment]::NewLine) '注册第二个动作验收账号'
    Assert-Pass ($secondaryRegisterJson.code -eq 200) '注册第二个动作验收账号'
    $secondaryXsrf = Login $secondaryEmail $secondaryJar
    $crossAccount = Invoke-Api $secondaryJar $secondaryXsrf "/api/agent/actions/$actionId/confirm" 'POST'
    Assert-Pass ($crossAccount.code -ne 200) '其他账号不能确认动作'

    Write-Host "Agent action acceptance: PASS (interview=$InterviewSessionId, action=$actionId, task=$taskId)" -ForegroundColor Cyan
} finally {
    if ($createdInterview -and -not [string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        try { Invoke-Api $primaryJar $xsrf "/api/interview/sessions/$InterviewSessionId" 'DELETE' | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $primaryJar -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $secondaryJar -Force -ErrorAction SilentlyContinue
}
