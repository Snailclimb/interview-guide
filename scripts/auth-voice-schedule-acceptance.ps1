param(
    [string]$BaseUrl = 'http://localhost:18083'
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
    $raw = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
        -H 'Content-Type: application/json' --data-raw $body
    $json = Get-JsonResponse $raw "注册 $Email"
    Assert-Pass ($json.code -eq 200) "注册 $Email"
}

function Login-User([string]$Email) {
    $jar = Join-Path $env:TEMP "interview-owner-$([guid]::NewGuid()).cookie"
    $body = @{ email = $Email; password = 'StrongPass123' } | ConvertTo-Json -Compress
    $raw = & curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d $body "$BaseUrl/api/auth/login"
    $json = Get-JsonResponse $raw "登录 $Email"
    Assert-Pass ($json.code -eq 200) "登录 $Email"
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw "登录 $Email 未下发 CSRF Cookie" }
    $parts = $xsrfLine -split '\s+'
    [pscustomobject]@{ Jar = $jar; Xsrf = $parts[$parts.Length - 1] }
}

function Invoke-OwnerApi(
    [string]$Jar,
    [string]$Xsrf,
    [string]$Path,
    [string]$Method = 'GET',
    [string]$Body = ''
) {
    # 登录后的会话 Cookie 和 CSRF Cookie 由登录步骤固定保存；避免 GET 响应的空 CSRF Set-Cookie 覆盖测试 Cookie。
    $args = @('-sS', '-b', $Jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $Xsrf")
        $args += @('-H', 'Content-Type: application/json')
    }
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        $args += @('--data-raw', $Body)
    }
    $raw = & curl.exe @args
    Get-JsonResponse $raw "$Method $Path"
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$emailA = "voice-schedule-a-$suffix@example.com"
$emailB = "voice-schedule-b-$suffix@example.com"
Register-User $emailA
$a = Login-User $emailA
Register-User $emailB
$b = Login-User $emailB

$scheduleId = $null
$voiceSessionId = $null
try {
    $scheduleBody = @{
        companyName = '归属验收公司'
        position = 'Java 工程师'
        interviewTime = (Get-Date).AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        interviewType = 'VIDEO'
    } | ConvertTo-Json -Compress
    $createdSchedule = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/interview-schedule' 'POST' $scheduleBody
    Assert-Pass ($createdSchedule.code -eq 200 -and $null -ne $createdSchedule.data.id) '账号 A 创建面试日程成功'
    $scheduleId = [string]$createdSchedule.data.id

    $aSchedules = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/interview-schedule'
    Assert-Pass (@($aSchedules.data | Where-Object { [string]$_.id -eq $scheduleId }).Count -eq 1) '账号 A 可以看到自己的面试日程'

    $bSchedules = Invoke-OwnerApi $b.Jar $b.Xsrf '/api/interview-schedule'
    Assert-Pass (@($bSchedules.data | Where-Object { [string]$_.id -eq $scheduleId }).Count -eq 0) '账号 B 看不到账号 A 的面试日程'

    $bScheduleDetail = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/interview-schedule/$scheduleId"
    Assert-Pass ($bScheduleDetail.code -ne 200) '账号 B 不能读取账号 A 的面试日程详情'

    $bScheduleDelete = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/interview-schedule/$scheduleId" 'DELETE'
    Assert-Pass ($bScheduleDelete.code -ne 200) '账号 B 不能删除账号 A 的面试日程'

    $aState = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/auth/me'
    Assert-Pass ($aState.code -eq 200 -and $aState.data.authenticated -eq $true) '账号 A 会话在语音面试前仍有效'

    $voiceBody = @{
        skillId = 'java-backend'
        difficulty = 'mid'
        introEnabled = $false
        techEnabled = $true
        projectEnabled = $false
        hrEnabled = $false
        plannedDuration = 5
    } | ConvertTo-Json -Compress
    $createdVoice = Invoke-OwnerApi $a.Jar $a.Xsrf '/api/voice-interview/sessions' 'POST' $voiceBody
    Assert-Pass ($createdVoice.code -eq 200 -and $null -ne $createdVoice.data.sessionId) `
        ("账号 A 创建语音面试会话成功（code=$($createdVoice.code), message=$($createdVoice.message)）")
    $voiceSessionId = [string]$createdVoice.data.sessionId

    $aVoiceDetail = Invoke-OwnerApi $a.Jar $a.Xsrf "/api/voice-interview/sessions/$voiceSessionId"
    Assert-Pass ($aVoiceDetail.code -eq 200) '账号 A 可以读取自己的语音面试会话'

    $bVoiceDetail = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/voice-interview/sessions/$voiceSessionId"
    Assert-Pass ($bVoiceDetail.code -ne 200) '账号 B 不能读取账号 A 的语音面试会话'

    $bVoiceList = Invoke-OwnerApi $b.Jar $b.Xsrf '/api/voice-interview/sessions'
    Assert-Pass (@($bVoiceList.data | Where-Object { [string]$_.sessionId -eq $voiceSessionId }).Count -eq 0) '账号 B 看不到账号 A 的语音面试会话'

    $bVoiceDelete = Invoke-OwnerApi $b.Jar $b.Xsrf "/api/voice-interview/sessions/$voiceSessionId" 'DELETE'
    Assert-Pass ($bVoiceDelete.code -ne 200) '账号 B 不能删除账号 A 的语音面试会话'

    Write-Host 'Voice and schedule ownership acceptance: PASS' -ForegroundColor Cyan
} finally {
    if ($null -ne $scheduleId) {
        try { Invoke-OwnerApi $a.Jar $a.Xsrf "/api/interview-schedule/$scheduleId" 'DELETE' | Out-Null } catch { }
    }
    if ($null -ne $voiceSessionId) {
        try { Invoke-OwnerApi $a.Jar $a.Xsrf "/api/voice-interview/sessions/$voiceSessionId" 'DELETE' | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $a.Jar -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $b.Jar -Force -ErrorAction SilentlyContinue
}
