<#!
.SYNOPSIS
    验证 Provider 模型价格的读取、数据库覆盖和清除回退。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$ProviderId = ''
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path $env:TEMP "pricing-acceptance-$([guid]::NewGuid()).cookie"

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 30 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Invoke-JsonApi([string]$Path, [string]$Method = 'GET', [object]$Body = $null) {
    $args = @('-sS', '-b', $jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $script:xsrf", '-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    return Parse-Json ((& curl.exe @args) -join [Environment]::NewLine) "$Method $Path"
}

try {
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $email = "pricing-acceptance-$suffix@example.com"
    $password = 'StrongPass123'
    $registerBody = @{ email = $email; password = $password; nickname = 'Pricing Acceptance' }
    $register = Parse-Json ((& curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
        -H 'Content-Type: application/json' --data-raw ($registerBody | ConvertTo-Json -Compress)) -join [Environment]::NewLine) '注册价格验收账号'
    Assert-Pass ($register.code -eq 200) '注册价格验收账号成功'

    $loginBody = @{ email = $email; password = $password } | ConvertTo-Json -Compress
    $login = Parse-Json ((& curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d $loginBody "$BaseUrl/api/auth/login") -join [Environment]::NewLine) '登录价格验收账号'
    Assert-Pass ($login.code -eq 200) '价格验收正式登录成功'

    $xsrfLine = Get-Content -LiteralPath $jar | Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '正式登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrf = $xsrfParts[$xsrfParts.Length - 1]

    $providers = Invoke-JsonApi '/api/llm-provider/list'
    Assert-Pass ($providers.code -eq 200 -and @($providers.data).Count -gt 0) '读取 Provider 列表成功'
    if ([string]::IsNullOrWhiteSpace($ProviderId)) {
        $ProviderId = [string](@($providers.data)[0].id)
    }
    $provider = @($providers.data) | Where-Object { $_.id -eq $ProviderId } | Select-Object -First 1
    Assert-Pass ($null -ne $provider) "找到目标 Provider：$ProviderId"

    $original = $provider.pricing
    $originalBody = @{
        inputPerMillion = if ($null -eq $original) { $null } else { $original.inputPerMillion }
        outputPerMillion = if ($null -eq $original) { $null } else { $original.outputPerMillion }
        currency = if ($null -eq $original -or [string]::IsNullOrWhiteSpace($original.currency)) { 'USD' } else { $original.currency }
    }

    $updated = Invoke-JsonApi "/api/llm-provider/$([Uri]::EscapeDataString($ProviderId))/pricing" 'PUT' @{
        inputPerMillion = 0.01
        outputPerMillion = 0.02
        currency = 'CNY'
    }
    Assert-Pass ($updated.code -eq 200 -and $updated.data.source -eq 'database' -and $updated.data.currency -eq 'CNY') '保存数据库价格覆盖成功'

    $afterUpdate = Invoke-JsonApi '/api/llm-provider/list'
    $updatedProvider = @($afterUpdate.data) | Where-Object { $_.id -eq $ProviderId } | Select-Object -First 1
    Assert-Pass ($updatedProvider.pricing.source -eq 'database' -and [decimal]$updatedProvider.pricing.inputPerMillion -eq 0.01) 'Provider 列表返回数据库价格覆盖'

    $cleared = Invoke-JsonApi "/api/llm-provider/$([Uri]::EscapeDataString($ProviderId))/pricing" 'PUT' $originalBody
    Assert-Pass ($cleared.code -eq 200) '清除或恢复价格配置成功'
    $afterClear = Invoke-JsonApi '/api/llm-provider/list'
    $clearedProvider = @($afterClear.data) | Where-Object { $_.id -eq $ProviderId } | Select-Object -First 1
    Assert-Pass ($clearedProvider.pricing.source -in @('config', 'none')) '清除数据库覆盖后回退到配置或未配置状态'
} finally {
    Remove-Item -LiteralPath $jar -Force -ErrorAction SilentlyContinue
}
