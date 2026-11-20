#requires -Version 7.0
param([ValidatePattern('^[A-Za-z0-9:_-]{1,80}$')][string]$KeyPrefix='shop:rbac:v1')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$key=$KeyPrefix+':rules'
# 延用现有规则发布器的 CAS 流程，避免覆盖其他已配置接口。
$raw=& docker compose -f (Join-Path $root 'shop-gateway/compose.yaml') exec -T redis-cache sh -c `
    'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw GET "$1"' inventory-rules $key
if ($LASTEXITCODE -ne 0) { throw 'Cannot read RBAC rules' }
$expected=''
$rules=@()
$old=($raw | Out-String).Trim()
if ($old) {
    $document=$old | ConvertFrom-Json
    $expected=[string]$document.version
    $rules=@($document.rules)
}
if (@($rules | Where-Object {$_.id -eq 'inventory-batch-receive'}).Count -gt 0) {
    Write-Output 'Inventory receipt rule already exists; review it before modifying.'
    return
}
$rules+=@{id='inventory-batch-receive';endpoint=@{methods=@('POST');paths=@('/api/v1/admin/inventory/batches')};
    routeIds=@('shop-inventory-api');permissions=@('inventory:write');match='ALL'}
$next=@{version=('inventory-'+[guid]::NewGuid().ToString('N'));rules=$rules}
$directory=Join-Path $root 'target/inventory-rules'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$file=Join-Path $directory ([guid]::NewGuid().ToString('N')+'.json')
try {
    [IO.File]::WriteAllText($file,($next|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Publish-DevRbac.ps1') -Type Rules -Document $file -KeyPrefix $KeyPrefix -ExpectedVersion $expected
} finally { Remove-Item -LiteralPath $file }
