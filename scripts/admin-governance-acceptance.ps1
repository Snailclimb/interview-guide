<#!
.SYNOPSIS
    验收正式登录下的管理员治理和 Agent 低敏审计查询。

.DESCRIPTION
    脚本只执行登录、当前身份、管理员账号列表、账号审计和 Agent 审计查询，
    不修改账号状态、角色或业务数据。使用 curl Cookie jar，避免 GET 响应中的
    空 CSRF Set-Cookie 覆盖登录时拿到的 token。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [Parameter(Mandatory = $true)]
    [string]$AdminEmail,
    [Parameter(Mandatory = $true)]
    [string]$AdminPassword
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path $env:TEMP "admin-governance-$([guid]::NewGuid()).cookie"

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
        $args += @('-H', "X-XSRF-TOKEN: $script:xsrf", '-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    return Parse-Json ((& curl.exe @args) -join [Environment]::NewLine) "$Method $Path"
}

try {
    $loginBody = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json -Compress
    $login = Parse-Json ((& curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d $loginBody "$BaseUrl/api/auth/login") -join [Environment]::NewLine) '管理员登录'
    Assert-Pass ($login.code -eq 200) '管理员登录成功'

    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '管理员登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrf = $xsrfParts[$xsrfParts.Length - 1]

    $me = Invoke-JsonApi '/api/auth/me'
    Assert-Pass ($me.code -eq 200 -and $me.data.authenticated -eq $true -and $me.data.user.role -eq 'ADMIN') `
        '当前会话识别为管理员'

    $users = Invoke-JsonApi '/api/admin/users?limit=20'
    Assert-Pass ($users.code -eq 200 -and $null -ne $users.data) '管理员可读取账号列表'

    $accountAudit = Invoke-JsonApi '/api/admin/audit/events?limit=20'
    Assert-Pass ($accountAudit.code -eq 200 -and $null -ne $accountAudit.data) '管理员可读取账号安全审计'

    $agentAudit = Invoke-JsonApi '/api/admin/agent-audit/events?limit=20'
    Assert-Pass ($agentAudit.code -eq 200 -and $null -ne $agentAudit.data) '管理员可读取 Agent 低敏审计'
    $sensitiveFields = @($agentAudit.data | ForEach-Object {
        $_.PSObject.Properties.Name -notcontains 'prompt' -and
        $_.PSObject.Properties.Name -notcontains 'answer' -and
        $_.PSObject.Properties.Name -notcontains 'toolInput'
    } | Where-Object { -not $_ })
    Assert-Pass ($sensitiveFields.Count -eq 0) 'Agent 审计响应不包含正文和工具参数'

    Write-Host 'Admin governance acceptance: PASS' -ForegroundColor Cyan
} finally {
    Remove-Item -LiteralPath $jar -Force -ErrorAction SilentlyContinue
}
