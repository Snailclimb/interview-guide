param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$ResumeFilePath = ''
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Get-JsonResponse([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 30 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Register-User([string]$Email) {
    $body = @{ email = $Email; password = 'StrongPass123'; nickname = $Email.Split('@')[0] } |
        ConvertTo-Json -Compress
    $raw = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" -H 'Content-Type: application/json' --data-raw $body
    $json = Get-JsonResponse $raw "注册 $Email"
    Assert-Pass ($json.code -eq 200) "注册 $Email"
}

function Login-User([string]$Email) {
    $jar = Join-Path $env:TEMP "interview-owner-$([guid]::NewGuid()).cookie"
    $body = @{ email = $Email; password = 'StrongPass123' } | ConvertTo-Json -Compress
    $raw = & curl.exe -sS -c $jar -H 'Content-Type: application/json' -d $body "$BaseUrl/api/auth/login"
    $json = Get-JsonResponse $raw "登录 $Email"
    Assert-Pass ($json.code -eq 200) "登录 $Email"
    $xsrfLine = Get-Content -LiteralPath $jar | Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw "登录 $Email 未下发 CSRF Cookie" }
    $parts = $xsrfLine -split '\s+'
    [pscustomobject]@{ Jar = $jar; Xsrf = $parts[$parts.Length - 1] }
}

function Invoke-OwnerApi(
    [string]$Jar,
    [string]$Xsrf,
    [string]$Path,
    [string]$Method = 'GET',
    [string]$FilePath = ''
) {
    $args = @('-sS', '-b', $Jar, '-c', $Jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') { $args += @('-H', "X-XSRF-TOKEN: $Xsrf") }
    if (-not [string]::IsNullOrWhiteSpace($FilePath)) {
        $args += @('-F', "file=@$FilePath;type=text/plain")
    }
    $raw = & curl.exe @args
    Get-JsonResponse $raw "$Method $Path"
}

if ([string]::IsNullOrWhiteSpace($ResumeFilePath)) {
    $ResumeFilePath = Join-Path $env:TEMP "interview-owner-$([guid]::NewGuid()).txt"
    Set-Content -LiteralPath $ResumeFilePath -Value "姓名：归属验收用户`n技能：Java、Spring Boot、PostgreSQL" -Encoding UTF8
}
if (-not (Test-Path -LiteralPath $ResumeFilePath -PathType Leaf)) {
    throw "测试简历文件不存在：$ResumeFilePath"
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$emailA = "ownership-a-$suffix@example.com"
$emailB = "ownership-b-$suffix@example.com"
Register-User $emailA
$a = Login-User $emailA
Register-User $emailB
$b = Login-User $emailB

try {
    $uploaded = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/resumes/upload' 'POST' $ResumeFilePath
    Assert-Pass ($uploaded.code -eq 200 -and $null -ne $uploaded.data.resume.id) '账号 A 上传简历成功'
    $resumeId = [string]$uploaded.data.resume.id

    $aList = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/resumes'
    Assert-Pass (@($aList.data | Where-Object { [string]$_.id -eq $resumeId }).Count -eq 1) '账号 A 可以看到自己的简历'

    $bList = Invoke-OwnerApi $b.Jar $b.Xsrf '/api/resumes'
    Assert-Pass (@($bList.data | Where-Object { [string]$_.id -eq $resumeId }).Count -eq 0) '账号 B 看不到账号 A 的简历'

    $bDetail = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/resumes/$resumeId/detail"
    Assert-Pass ($bDetail.code -ne 200) '账号 B 不能读取账号 A 的简历详情'

    $bDelete = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/resumes/$resumeId" 'DELETE'
    Assert-Pass ($bDelete.code -ne 200) '账号 B 不能删除账号 A 的简历'

    $aDetail = Invoke-OwnerApi $a.Jar $a.Xsrf "/api/resumes/$resumeId/detail"
    Assert-Pass ($aDetail.code -eq 200) '账号 A 仍可读取自己的简历详情'

    Write-Host 'Formal auth ownership acceptance: PASS' -ForegroundColor Cyan
} finally {
    Remove-Item -LiteralPath $a.Jar -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $b.Jar -Force -ErrorAction SilentlyContinue
    if ($ResumeFilePath -like "$env:TEMP\interview-owner-*.txt") {
        Remove-Item -LiteralPath $ResumeFilePath -Force -ErrorAction SilentlyContinue
    }
}
