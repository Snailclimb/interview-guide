<#
.SYNOPSIS
    验收知识库、RAG 会话在正式登录模式下的用户隔离与历史共享兼容。
#>

param(
    [string]$BaseUrl = 'http://localhost:18083'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 50 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Register-User([string]$Email) {
    $body = @{ email = $Email; password = 'StrongPass123'; nickname = $Email.Split('@')[0] } |
        ConvertTo-Json -Compress
    $raw = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
        -H 'Content-Type: application/json' --data-raw $body
    $json = Parse-Json ($raw -join [Environment]::NewLine) "注册 $Email"
    Assert-Pass ($json.code -eq 200) "注册 $Email"
}

function Login-User([string]$Email) {
    $jar = Join-Path $env:TEMP "interview-kb-owner-$([guid]::NewGuid()).cookie"
    $headers = Join-Path $env:TEMP "interview-kb-owner-$([guid]::NewGuid()).headers"
    $body = @{ email = $Email; password = 'StrongPass123' } | ConvertTo-Json -Compress
    $raw = & curl.exe -sS -D $headers -c $jar -H 'Content-Type: application/json' `
        -d $body "$BaseUrl/api/auth/login"
    $json = Parse-Json ($raw -join [Environment]::NewLine) "登录 $Email"
    Assert-Pass ($json.code -eq 200) "登录 $Email"
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match 'XSRF-TOKEN' } | Select-Object -Last 1
    if ($null -ne $xsrfLine) {
        $parts = $xsrfLine -split "`t"
        $xsrf = $parts[$parts.Length - 1]
    } else {
        $headerLine = Get-Content -LiteralPath $headers |
            Where-Object { $_ -match '(?i)^Set-Cookie:.*XSRF-TOKEN=' } | Select-Object -Last 1
        if ($null -eq $headerLine -or $headerLine -notmatch '(?i)^Set-Cookie:.*XSRF-TOKEN=([^;]+)') {
            throw "登录 $Email 未下发 CSRF Cookie"
        }
        $xsrf = $Matches[1]
        Add-Content -LiteralPath $jar -Value "127.0.0.1`tFALSE`t/`tFALSE`t0`tXSRF-TOKEN`t$xsrf"
    }
    Remove-Item -LiteralPath $headers -Force -ErrorAction SilentlyContinue
    [pscustomobject]@{ Jar = $jar; Xsrf = $xsrf }
}

function Get-Xsrf([string]$AuthJar, [string]$Fallback) {
    $line = Get-Content -LiteralPath $AuthJar |
        Where-Object { $_ -match 'XSRF-TOKEN' } | Select-Object -Last 1
    if ($null -ne $line) {
        $parts = $line -split "`t"
        return $parts[$parts.Length - 1]
    }
    return $Fallback
}

function Invoke-JsonApi(
    [string]$AuthJar,
    [string]$Xsrf,
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $args = @('-sS', '-b', $AuthJar, '-c', $AuthJar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $(Get-Xsrf $AuthJar $Xsrf)", '-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $raw = & curl.exe @args
    Parse-Json ($raw -join [Environment]::NewLine) "$Method $Path"
}

function Upload-KnowledgeBase([string]$AuthJar, [string]$Xsrf, [string]$FilePath) {
    $raw = & curl.exe -sS -b $AuthJar -c $AuthJar -X POST "$BaseUrl/api/knowledgebase/upload" `
        -H "X-XSRF-TOKEN: $(Get-Xsrf $AuthJar $Xsrf)" -F "file=@$FilePath;type=text/plain" `
        -F 'name=隔离验收知识库' -F 'category=验收'
    Parse-Json ($raw -join [Environment]::NewLine) '上传知识库'
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$emailA = "kb-owner-a-$suffix@example.com"
$emailB = "kb-owner-b-$suffix@example.com"
$file = Join-Path $env:TEMP "interview-kb-owner-$suffix.txt"
Set-Content -LiteralPath $file -Value "知识库隔离验收 $suffix`nJava Spring Boot PostgreSQL" -Encoding UTF8

$a = $null
$b = $null
$kbId = $null
$ragSessionId = $null
try {
    Register-User $emailA
    $a = Login-User $emailA
    Register-User $emailB
    $b = Login-User $emailB

    $uploaded = Upload-KnowledgeBase $a.Jar $a.Xsrf $file
    Assert-Pass ($uploaded.code -eq 200 -and $null -ne $uploaded.data.knowledgeBase.id) '账号 A 上传私有知识库成功'
    $kbId = [string]$uploaded.data.knowledgeBase.id

    $aList = Invoke-JsonApi $a.Jar $a.Xsrf '/api/knowledgebase/list'
    Assert-Pass (@($aList.data | Where-Object { [string]$_.id -eq $kbId }).Count -eq 1) '账号 A 可以看到自己的知识库'

    $bList = Invoke-JsonApi $b.Jar $b.Xsrf '/api/knowledgebase/list'
    Assert-Pass (@($bList.data | Where-Object { [string]$_.id -eq $kbId }).Count -eq 0) '账号 B 看不到账号 A 的私有知识库'

    $bDetail = Invoke-JsonApi $b.Jar $b.Xsrf "/api/knowledgebase/$kbId"
    Assert-Pass ($bDetail.code -ne 200) '账号 B 不能读取账号 A 的知识库详情'

    $bQuery = Invoke-JsonApi $b.Jar $b.Xsrf '/api/knowledgebase/query' 'POST' @{
        knowledgeBaseIds = @([long]$kbId)
        question = '隔离验收问题'
    }
    Assert-Pass ($bQuery.code -ne 200) '账号 B 不能通过查询接口越权读取账号 A 的知识库'

    $rag = Invoke-JsonApi $a.Jar $a.Xsrf '/api/rag-chat/sessions' 'POST' @{
        title = "隔离验收会话 $suffix"
        knowledgeBaseIds = @([long]$kbId)
    }
    Assert-Pass ($rag.code -eq 200 -and $null -ne $rag.data.id) '账号 A 创建私有 RAG 会话成功'
    $ragSessionId = [string]$rag.data.id

    $bSessions = Invoke-JsonApi $b.Jar $b.Xsrf '/api/rag-chat/sessions'
    Assert-Pass (@($bSessions.data | Where-Object { [string]$_.id -eq $ragSessionId }).Count -eq 0) '账号 B 看不到账号 A 的 RAG 会话'

    $bRagDetail = Invoke-JsonApi $b.Jar $b.Xsrf "/api/rag-chat/sessions/$ragSessionId"
    Assert-Pass ($bRagDetail.code -ne 200) '账号 B 不能读取账号 A 的 RAG 会话'

    $aDetail = Invoke-JsonApi $a.Jar $a.Xsrf "/api/knowledgebase/$kbId"
    Assert-Pass ($aDetail.code -eq 200) '账号 A 仍可读取自己的知识库详情'

    Write-Host 'Knowledge base owner isolation acceptance: PASS' -ForegroundColor Cyan
}
finally {
    if ($null -ne $a -and $null -ne $kbId) {
        try { Invoke-JsonApi $a.Jar $a.Xsrf "/api/knowledgebase/$kbId" 'DELETE' | Out-Null } catch { }
    }
    if ($null -ne $a) { Remove-Item -LiteralPath $a.Jar -Force -ErrorAction SilentlyContinue }
    if ($null -ne $b) { Remove-Item -LiteralPath $b.Jar -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
}
