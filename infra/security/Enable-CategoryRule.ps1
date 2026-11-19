#requires -Version 7.0
param([ValidatePattern('^[A-Za-z0-9:_-]{1,80}$')][string]$KeyPrefix='shop:rbac:v1')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$key=$KeyPrefix+':rules'
# 读取旧规则，保留所有已有接口；只追加本次创建分类规则，再通过 CAS 发布。
$raw=& docker compose -f (Join-Path $root 'shop-gateway/compose.yaml') exec -T redis-cache sh -c `
    'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw GET "$1"' category-rules $key
if ($LASTEXITCODE -ne 0) { throw 'Cannot read RBAC rules' }
$old=($raw | Out-String).Trim()
$expected=''
$rules=@()
if ($old) {
    $document=$old | ConvertFrom-Json
    $expected=[string]$document.version
    $rules=@($document.rules)
}
if (@($rules | Where-Object {$_.id -eq 'product-category-create'}).Count -gt 0) {
    Write-Output 'Category rule already exists; review it before modifying.'
    return
}
$rules+=@{id='product-category-create';endpoint=@{methods=@('POST');paths=@('/api/v1/admin/categories')};
    routeIds=@();permissions=@('product:write');match='ALL'}
$next=@{version=('category-'+[guid]::NewGuid().ToString('N'));rules=$rules}
$directory=Join-Path $root 'target/category-rules'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$file=Join-Path $directory ([guid]::NewGuid().ToString('N')+'.json')
try {
    [IO.File]::WriteAllText($file,($next|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Publish-DevRbac.ps1') -Type Rules -Document $file -KeyPrefix $KeyPrefix -ExpectedVersion $expected
} finally { Remove-Item -LiteralPath $file }
