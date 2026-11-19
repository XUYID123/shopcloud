param(
    [Parameter(Mandatory=$true)][ValidateSet('Rules','User')][string]$Type,
    [Parameter(Mandatory=$true)][string]$Document,
    [string]$ExpectedVersion = '',
    [ValidatePattern('^[A-Za-z0-9:_-]{1,80}$')][string]$KeyPrefix = 'shop:rbac:v1',
    [ValidateRange(1,300)][int]$UserTtlSeconds = 30
)
$ErrorActionPreference = 'Stop'
# 仅供本地开发验证发布协议；生产由受权限保护的后台在数据库提交后发布。
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$composeFile = Join-Path $projectRoot 'shop-gateway/compose.yaml'
$luaFile = Join-Path $projectRoot 'shop-gateway/src/main/resources/redis/publish-rbac.lua'
$json = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Document).Path)
$record = $json | ConvertFrom-Json
if ($record.version -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Invalid document version' }
if ($ExpectedVersion -ne '' -and $ExpectedVersion -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Invalid expected version' }
if ($Type -eq 'User') {
    if ($record.userId -notmatch '^[1-9][0-9]{0,18}$') { throw 'Invalid user id' }
    $redisKey = $KeyPrefix + ':user:{' + $record.userId + '}'
    $epochKey = $redisKey + ':epoch'
    $ttlMillis = $UserTtlSeconds * 1000
} else {
    $redisKey = $KeyPrefix + ':rules'
    $epochKey = '-'
    $ttlMillis = 0
}
function ConvertTo-TaskBase64([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}
# 所有文档和 Lua 以 Base64 参数传入固定脚本，不将 JSON 当作 shell 代码拼接。
$luaEncoded = ConvertTo-TaskBase64 ([System.IO.File]::ReadAllText($luaFile))
$documentEncoded = ConvertTo-TaskBase64 $json
$expectedEncoded = if ($ExpectedVersion -eq '') { '-' } else { ConvertTo-TaskBase64 $ExpectedVersion }
$script = 'lua=$(printf %s "$1" | base64 -d); document=$(printf %s "$2" | base64 -d); expected=""; if [ "$3" != "-" ]; then expected=$(printf %s "$3" | base64 -d); fi; if [ "$5" = "-" ]; then exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw EVAL "$lua" 1 "$4" "$expected" "$document" "$6" "$7" "$8"; else exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw EVAL "$lua" 2 "$4" "$5" "$expected" "$document" "$6" "$7" "$8"; fi'
$result = & docker compose -f $composeFile exec -T redis-cache sh -c $script publish-rbac `
    $luaEncoded $documentEncoded $expectedEncoded $redisKey $epochKey ([string]$record.version) ([string]$ttlMillis) ([guid]::NewGuid().ToString())
if ($LASTEXITCODE -ne 0) { throw 'Docker/Redis publication failed' }
$written = 0L
if (-not [long]::TryParse(($result -join '').Trim(), [ref]$written) -or $written -lt 0) {
    throw 'Publication rejected: version conflict or invalid source document'
}
Write-Output "Stored $Type version $($record.version); no Pub/Sub; user TTL: $ttlMillis ms"
