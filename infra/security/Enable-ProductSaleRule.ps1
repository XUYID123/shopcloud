#requires -Version 7.0
param([ValidatePattern('^[A-Za-z0-9:_-]{1,80}$')][string]$KeyPrefix='shop:rbac:v1')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$key=$KeyPrefix+':rules'
# 读取旧规则，保留所有已有接口；只追加本次商品销售状态规则，再通过 CAS 发布。
$raw=& docker compose -f (Join-Path $root 'shop-gateway/compose.yaml') exec -T redis-cache sh -c `
    'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw GET "$1"' product-rules $key
if ($LASTEXITCODE -ne 0) { throw 'Cannot read RBAC rules' }
$old=($raw | Out-String).Trim()
$expected=''
$rules=@()
if ($old) {
    $document=$old | ConvertFrom-Json
    $expected=[string]$document.version
    $rules=@($document.rules)
}
if (@($rules | Where-Object {$_.id -eq 'product-sale-status'}).Count -gt 0) {
    Write-Output 'Sale status rule already exists; review it before modifying.'
    return
}
$rules+=@{id='product-sale-status';endpoint=@{methods=@('PUT');paths=@('/api/v1/admin/products/*/sale-status')};
    routeIds=@();permissions=@('product:write');match='ALL'}
$next=@{version=('sale-'+[guid]::NewGuid().ToString('N'));rules=$rules}
$directory=Join-Path $root 'target/sale-rules'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$file=Join-Path $directory ([guid]::NewGuid().ToString('N')+'.json')
try {
    [IO.File]::WriteAllText($file,($next|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Publish-DevRbac.ps1') -Type Rules -Document $file -KeyPrefix $KeyPrefix -ExpectedVersion $expected
} finally { Remove-Item -LiteralPath $file }
