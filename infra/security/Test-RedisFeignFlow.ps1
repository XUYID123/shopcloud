#requires -Version 7.0
param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = Join-Path $JavaHome 'bin/java.exe'
$namespace = 'test:feign-smoke:' + [guid]::NewGuid().ToString('N')
$username = 'feign_' + [guid]::NewGuid().ToString('N').Substring(0,16)
$resultDirectory = Join-Path $projectRoot 'target/redis-feign-smoke'
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
# 每次验收重新出具结果，失败时不能遗留上一次的 passed=true 报告造成误判。
$resultFile = Join-Path $resultDirectory 'result.json'
if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile }
$userProcess = $null; $gatewayProcess = $null; $userId = $null
$savedEnvironment = @{}
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start(); $value = $listener.LocalEndpoint.Port; $listener.Stop(); return $value
}
function Read-LiveLog([string]$Path) {
    # Windows 下重定向/Logback 仍持有写句柄，读取端也必须允许并发写入。
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Wait-Started($Process, [int]$Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "Test service exited; inspect $resultDirectory" }
        try {
            # 必须等到应用实际处理 HTTP 并经过请求入口；TCP 端口短暂可连接不代表就绪。
            $response = Invoke-WebRequest "http://127.0.0.1:$Port/__smoke_ready__" -SkipHttpErrorCheck -TimeoutSec 1
            if ($response.Headers.ContainsKey('X-Request-Id')) { return }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    throw 'Test service startup timed out'
}
function Invoke-TestSql([string]$Sql) {
    $Sql | & docker compose -f (Join-Path $projectRoot 'shop-user/compose.yaml') exec -T mysql-user sh -c `
        'exec env MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw 'Test SQL failed' }
}
function Invoke-TestRedis([string]$Service, [string[]]$Arguments) {
    $value = & docker compose -f (Join-Path $projectRoot 'shop-gateway/compose.yaml') exec -T $Service sh -c `
        'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw "$@"' smoke @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Test Redis operation failed' }
    return $value
}
Push-Location $projectRoot
try {
    $env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3307/flour_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
    $env:SPRING_DATASOURCE_USERNAME = 'shop_user'
    $env:SPRING_DATASOURCE_PASSWORD = if ($env:SHOP_USER_MYSQL_PASSWORD) { $env:SHOP_USER_MYSQL_PASSWORD } else { 'shop_user_local_dev' }
    $privateKey = [Uri]::new((Join-Path $projectRoot 'infra/local-secrets/jwt-private.pem')).AbsoluteUri
    $publicKey = [Uri]::new((Join-Path $projectRoot 'infra/local-secrets/jwt-public.pem')).AbsoluteUri
    $internalPrivate=[Uri]::new((Join-Path $projectRoot 'infra/local-secrets/gateway-internal-private.pem')).AbsoluteUri
    $internalPublic=[Uri]::new((Join-Path $projectRoot 'infra/local-secrets/gateway-internal-public.pem')).AbsoluteUri
    $userPort = Get-FreePort
    $userProcess = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -WorkingDirectory $projectRoot `
        -ArgumentList @('-jar',(Join-Path $projectRoot 'shop-user/target/shop-user-1.0-SNAPSHOT.jar'),
            "--server.port=$userPort",'--spring.docker.compose.enabled=false','--spring.cloud.nacos.discovery.enabled=false',
            "--shop.permissions.key-prefix=$namespace", "--shop.auth.key-prefix=$namespace", '--shop.auth.enabled=true',
            "--shop.internal-auth.public-key-location=$internalPublic", "--shop.auth.private-key-location=$privateKey", "--shop.auth.public-key-location=$publicKey",
            '--logging.level.org.spring.shopcommon.trace.TraceServletFilter=DEBUG', "--logging.file.path=$resultDirectory/rolling") `
        -RedirectStandardOutput (Join-Path $resultDirectory 'user.log') -RedirectStandardError (Join-Path $resultDirectory 'user-error.log')
    Wait-Started $userProcess $userPort
    $rules = @{ version='smoke-r1'; rules=@(@{ id='smoke-user'; endpoint=@{ methods=@('GET'); paths=@('/api/v1/user') };
        routeIds=@(); permissions=@('order:create'); match='ALL' }) } | ConvertTo-Json -Depth 8
    $rulesFile = Join-Path $resultDirectory 'rules.json'
    [IO.File]::WriteAllText($rulesFile,$rules,[Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Publish-DevRbac.ps1') -Type Rules -Document $rulesFile -KeyPrefix $namespace | Out-Null
    $gatewayPort = Get-FreePort
    $gatewayProcess = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -WorkingDirectory $projectRoot `
        -ArgumentList @('-jar',(Join-Path $projectRoot 'shop-gateway/target/shop-gateway-1.0-SNAPSHOT.jar'),
            "--server.port=$gatewayPort",'--spring.docker.compose.enabled=false','--spring.cloud.nacos.discovery.enabled=false',
            "--spring.cloud.discovery.client.simple.instances.shop-user[0].uri=http://127.0.0.1:$userPort",
            "--gateway.internal-jwt.private-key-location=$internalPrivate", "--gateway.auth.public-key-location=$publicKey","--gateway.auth.key-prefix=$namespace", '--gateway.auth.enabled=true',
            "--gateway.protection.anti-abuse.key-prefix=$namespace",
            '--logging.level.org.spring.shopgateway.web.GatewayRequestFilter=DEBUG', "--logging.file.path=$resultDirectory/rolling") `
        -RedirectStandardOutput (Join-Path $resultDirectory 'gateway.log') -RedirectStandardError (Join-Path $resultDirectory 'gateway-error.log')
    Wait-Started $gatewayProcess $gatewayPort
    $registered = Invoke-RestMethod -Uri "http://127.0.0.1:$gatewayPort/api/v1/users" -Method Post -ContentType 'application/json' `
        -Body (@{ username=$username; password='FlourSmoke123!' } | ConvertTo-Json -Compress)
    $userId = [string]$registered.data.userId
    if ($userId -notmatch '^[1-9][0-9]{0,18}$') { throw 'Registration did not return a valid test user' }
    # 通过真实网关登录，使用真实 MyBatis-Plus/BCrypt/JJWT。凭据只留内存，不输出或写入报告。
    $login = Invoke-RestMethod -Uri "http://127.0.0.1:$gatewayPort/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username=$username; password='FlourSmoke123!' } | ConvertTo-Json -Compress)
    if ($login.code -ne 'OK' -or !$login.data.accessToken -or !$login.data.refreshToken) { throw 'Real login did not return both tokens' }
    $oldRefresh = [string]$login.data.refreshToken
    $refreshed = Invoke-RestMethod -Uri "http://127.0.0.1:$gatewayPort/api/auth/refresh" -Method Post -ContentType 'application/json' `
        -Body (@{ refreshToken=$oldRefresh } | ConvertTo-Json -Compress)
    $token = [string]$refreshed.data.accessToken
    $refreshToken = [string]$refreshed.data.refreshToken
    if (!$token -or !$refreshToken -or $oldRefresh -eq $refreshToken) { throw 'Refresh token rotation failed' }
    $replay = Invoke-WebRequest -Uri "http://127.0.0.1:$gatewayPort/api/auth/refresh" -Method Post -ContentType 'application/json' `
        -Body (@{ refreshToken=$oldRefresh } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$replay.StatusCode -ne 401 -or ($replay.Content | ConvertFrom-Json).code -ne 'INVALID_REFRESH_TOKEN') { throw 'Old refresh token was reusable' }
    $refreshKey = $namespace + ':refresh_token:{' + $userId + '}'
    $storedRefresh = (Invoke-TestRedis 'redis-gateway' @('GET',$refreshKey) | Out-String).Trim()
    if ($storedRefresh -ne $refreshToken) { throw 'Refresh token not stored in security Redis' }
    if ((Invoke-TestRedis 'redis-cache' @('EXISTS',$refreshKey) | Out-String).Trim() -ne '0') { throw 'Refresh token leaked into business Redis' }
    $traceId = [guid]::NewGuid().ToString('N').Substring(0,16)
    $headers = @{ Authorization="Bearer $token"; 'X-User-Id'='999'; 'X-Internal-Token'='spoofed';
        'X-Trace-Id'=$traceId; 'X-Request-Id'='must-not-win' }
    $allowed = Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/user" -Headers $headers -SkipHttpErrorCheck
    # TestController 主动返回业务 400，证明请求通过鉴权并到达真实 shop-user。
    if ([int]$allowed.StatusCode -ne 400) { throw "Expected downstream business 400, got $($allowed.StatusCode)" }
    $cacheKey = $namespace + ':user:{' + $userId + '}'
    $cached = (Invoke-TestRedis 'redis-cache' @('GET',$cacheKey) | Out-String).Trim() | ConvertFrom-Json
    if ($cached.userId -ne $userId -or $cached.permissions -notcontains 'order:create') { throw 'Feign did not cache database permissions' }
    # 只修改刚创建的测试用户；同事务写失效任务，实际 worker 负责删缓存。
    Invoke-TestSql "START TRANSACTION; DELETE FROM user_role WHERE user_id=$userId; INSERT INTO permission_cache_invalidation(user_id,key_prefix,event_id) VALUES ($userId,'$namespace',UUID()) AS incoming ON DUPLICATE KEY UPDATE event_id=incoming.event_id,next_attempt_at=CURRENT_TIMESTAMP(3); COMMIT;" | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $exists = (Invoke-TestRedis 'redis-cache' @('EXISTS',$cacheKey) | Out-String).Trim()
        if ($exists -eq '0') { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($exists -ne '0') { throw 'Database invalidation worker did not delete cache' }
    $denied = Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/user" -Headers $headers -SkipHttpErrorCheck
    if ([int]$denied.StatusCode -ne 403) { throw "Expected 403 after database revocation, got $($denied.StatusCode)" }
    $logout = Invoke-RestMethod -Uri "http://127.0.0.1:$gatewayPort/api/auth/logout" -Method Post -ContentType 'application/json' `
        -Body (@{ refreshToken=$refreshToken } | ConvertTo-Json -Compress)
    if ($logout.code -ne 'OK') { throw 'Logout failed' }
    $afterLogout = Invoke-WebRequest -Uri "http://127.0.0.1:$gatewayPort/api/auth/refresh" -Method Post -ContentType 'application/json' `
        -Body (@{ refreshToken=$refreshToken } | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$afterLogout.StatusCode -ne 401) { throw 'Logout did not revoke refresh credentials' }
    foreach ($response in @($allowed,$denied)) {
        $body = $response.Content | ConvertFrom-Json
        if ($body.traceId -ne $response.Headers['X-Request-Id'][0]) { throw 'Request ID propagation mismatch' }
        if ($body.traceId -ne $traceId -or $response.Headers['X-Trace-Id'][0] -ne $traceId) { throw 'Trace ID propagation mismatch' }
    }
    # 检查真实日志 Pattern 读到了 MDC，避免只看到 HTTP 头一致就误判日志链路已打通。
    $gatewayLog = Read-LiveLog (Join-Path $resultDirectory 'gateway.log')
    $userLog = Read-LiveLog (Join-Path $resultDirectory 'user.log')
    if (!$gatewayLog.Contains("[$traceId]") -or !$userLog.Contains("[$traceId]")) { throw 'Trace ID missing from console logs' }
    foreach ($service in @('gateway-service','shop-user')) {
        $fileLog = Read-LiveLog (Join-Path $resultDirectory "rolling/$service.log")
        if (!$fileLog.Contains("[$traceId]")) { throw "Trace ID missing from $service file logs" }
    }
    $report = [ordered]@{ passed=$true; gatewayToRealUserViaOpenFeign=$true; mysqlColdLoad=$true;
        redisRefill=$true; databaseOutboxInvalidation=$true; nextRequestForbidden=$true; requestIdsConsistent=$true;
        loginThroughGateway=$true; refreshThroughGateway=$true; refreshReplayRejected=$true; authRedisIsolation=$true; logoutRevokesRefresh=$true;
        traceHeadersConsistent=$true; gatewayMdcLog=$true; userMdcLog=$true; fileTraceLogs=$true }
    $json = $report | ConvertTo-Json
    [IO.File]::WriteAllText($resultFile,$json,[Text.UTF8Encoding]::new($false))
    Write-Output $json
} finally {
    foreach ($process in @($gatewayProcess,$userProcess)) {
        if ($null -ne $process -and !$process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
    if ($userId -match '^[1-9][0-9]{0,18}$') {
        Invoke-TestSql "DELETE FROM permission_cache_invalidation WHERE user_id=$userId AND key_prefix='$namespace'; DELETE FROM user_role WHERE user_id=$userId; DELETE FROM user_account WHERE id=$userId AND username='$username';" | Out-Null
    }
    foreach ($service in @('redis-cache','redis-gateway')) {
        $keys = @(Invoke-TestRedis $service @('--scan','--pattern',($namespace + ':*')) | Where-Object { $_ })
        if ($keys.Count -gt 0) { Invoke-TestRedis $service (@('DEL') + $keys) | Out-Null }
    }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
    Pop-Location
}
