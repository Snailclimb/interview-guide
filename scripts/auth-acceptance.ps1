param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

function Invoke-AuthApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $null,
        [hashtable]$Headers = $null
    )

    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        UseBasicParsing = $true
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = $Body | ConvertTo-Json -Compress
    }
    if ($null -ne $Session) {
        $params.WebSession = $Session
    }
    if ($null -ne $Headers) {
        $params.Headers = $Headers
    }
    return Invoke-WebRequest @params
}

function Get-Json($Response) {
    return $Response.Content | ConvertFrom-Json
}

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

$anonymousMe = Invoke-AuthApi -Method GET -Path '/api/auth/me'
$anonymousMeJson = Get-Json $anonymousMe
Assert-Pass ($anonymousMe.StatusCode -eq 200 -and $anonymousMeJson.data.required -eq $true -and $anonymousMeJson.data.authenticated -eq $false) '未登录状态可识别且 required=true'

$anonymousBusiness = Invoke-AuthApi -Method GET -Path '/api/interview/sessions'
$anonymousBusinessJson = Get-Json $anonymousBusiness
Assert-Pass ($anonymousBusiness.StatusCode -eq 401 -and $anonymousBusinessJson.code -eq 401) '未登录访问业务接口返回 401'

$email = "auth-$(Get-Date -Format 'yyyyMMddHHmmssfff')@example.com"
$password = 'StrongPass123'
$register = Invoke-AuthApi -Method POST -Path '/api/auth/register' -Body @{
    email = $email
    password = $password
    nickname = '认证验收用户'
}
$registerJson = Get-Json $register
Assert-Pass ($register.StatusCode -eq 200 -and $registerJson.success -eq $true -and $registerJson.data.email -eq $email) '注册成功且邮箱标准化'

$duplicate = Invoke-AuthApi -Method POST -Path '/api/auth/register' -Body @{
    email = $email.ToUpperInvariant()
    password = $password
    nickname = '重复用户'
}
$duplicateJson = Get-Json $duplicate
Assert-Pass ($duplicateJson.code -eq 12001) '重复邮箱被拒绝'

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$login = Invoke-AuthApi -Method POST -Path '/api/auth/login' -Body @{
    email = $email
    password = $password
} -Session $session
$loginJson = Get-Json $login
Assert-Pass ($login.StatusCode -eq 200 -and $loginJson.success -eq $true -and $session.Cookies.Count -ge 1) '登录成功并下发会话 Cookie'

$me = Invoke-AuthApi -Method GET -Path '/api/auth/me' -Session $session
$meJson = Get-Json $me
Assert-Pass ($meJson.data.authenticated -eq $true -and $meJson.data.user.email -eq $email) '会话可恢复当前用户'

$business = Invoke-AuthApi -Method GET -Path '/api/interview/sessions' -Session $session
$businessJson = Get-Json $business
Assert-Pass ($business.StatusCode -eq 200 -and $businessJson.success -eq $true) '登录后业务接口可访问'

$csrfCookie = $session.Cookies.GetCookies("$BaseUrl/") | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
Assert-Pass ($null -ne $csrfCookie -and -not [string]::IsNullOrWhiteSpace($csrfCookie.Value)) '登录会话下发 CSRF Cookie'
$csrfBlocked = Invoke-AuthApi -Method DELETE -Path '/api/agent/memory' -Session $session
Assert-Pass ($csrfBlocked.StatusCode -in @(401, 403)) '缺少 CSRF Header 的写请求被拒绝'
$csrfAllowed = Invoke-AuthApi -Method DELETE -Path '/api/agent/memory' -Session $session -Headers @{ 'X-XSRF-TOKEN' = $csrfCookie.Value }
Assert-Pass ($csrfAllowed.StatusCode -eq 200) '携带 CSRF Header 的写请求可访问'

$sessionItems = @($businessJson.data)
if ($sessionItems.Count -ge 1) {
    Assert-Pass $true '正式会话可读取面试记录'
    $interviewSessionId = [string]$sessionItems[0].sessionId
    $agentConversationPath = "/api/agent/interview-sessions/$([Uri]::EscapeDataString($interviewSessionId))/conversation"
    $agentConversation = Invoke-AuthApi -Method GET -Path $agentConversationPath -Session $session
    $agentConversationJson = Get-Json $agentConversation
    Assert-Pass ($agentConversation.StatusCode -eq 200 -and $agentConversationJson.success -eq $true) '正式会话可访问 Agent 对话'
    $agentThreadsPath = "/api/agent/interview-sessions/$([Uri]::EscapeDataString($interviewSessionId))/conversations"
    $curlBody = '{"title":"正式认证验收线程-curl"}'
    $curlJar = Join-Path $env:TEMP "interview-auth-$([guid]::NewGuid()).cookie"
    $null = & curl.exe -sS -c $curlJar -H 'Content-Type: application/json' -d (@{ email = $email; password = $password } | ConvertTo-Json -Compress) "$BaseUrl/api/auth/login"
    $xsrfLine = Get-Content $curlJar | Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    $xsrfParts = $xsrfLine -split '\s+'
    $xsrfValue = $xsrfParts[$xsrfParts.Length - 1]
    $curlJarPost = & curl.exe -sS -b $curlJar -c $curlJar -X POST "$BaseUrl$agentThreadsPath" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $xsrfValue" --data-raw $curlBody
    $curlJarJson = $curlJarPost | ConvertFrom-Json
    Remove-Item -LiteralPath $curlJar -Force -ErrorAction SilentlyContinue
    Assert-Pass ($curlJarJson.code -eq 200 -and -not [string]::IsNullOrWhiteSpace([string]$curlJarJson.data.agentSessionId)) '正式会话可创建 Agent 对话线程'
} else {
    Write-Host '[SKIP] 当前账号没有已归属的面试记录，跳过 Agent 会话依赖测试' -ForegroundColor Yellow
}

$spoofed = Invoke-AuthApi -Method GET -Path '/api/interview/sessions' -Session $session -Headers @{ 'X-User-Id' = 'spoofed-user' }
Assert-Pass ($spoofed.StatusCode -eq 200) '正式会话优先于伪造的旧身份 Header'

$logout = Invoke-AuthApi -Method POST -Path '/api/auth/logout' -Session $session
$logoutJson = Get-Json $logout
Assert-Pass ($logout.StatusCode -eq 200 -and $logoutJson.success -eq $true) '退出登录成功'

$meAfterLogout = Invoke-AuthApi -Method GET -Path '/api/auth/me' -Session $session
$meAfterLogoutJson = Get-Json $meAfterLogout
Assert-Pass ($meAfterLogoutJson.data.authenticated -eq $false) '退出后会话失效'

$businessAfterLogout = Invoke-AuthApi -Method GET -Path '/api/interview/sessions' -Session $session
$businessAfterLogoutJson = Get-Json $businessAfterLogout
Assert-Pass ($businessAfterLogout.StatusCode -eq 401 -and $businessAfterLogoutJson.code -eq 401) '退出后业务接口再次返回 401'

Write-Host 'Formal auth acceptance: PASS' -ForegroundColor Cyan
