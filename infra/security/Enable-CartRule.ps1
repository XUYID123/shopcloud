#requires -Version 7.0
param([ValidatePattern('^[A-Za-z0-9:_-]{1,80}$')][string]$KeyPrefix = 'shop:rbac:v1')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ids = @('cart-list', 'cart-change')
$cartRules = @((Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'examples/rbac-rules.json') | ConvertFrom-Json).rules |
    Where-Object { $_.id -in $ids })
if ($cartRules.Count -ne $ids.Count) { throw 'The default cart rules are incomplete' }
$raw = & docker compose -f (Join-Path $root 'shop-gateway/compose.yaml') exec -T redis-cache sh -c `
    'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw GET "$1"' cart-rules ($KeyPrefix + ':rules')
if ($LASTEXITCODE -ne 0) { throw 'Cannot read RBAC rules' }

$expected = ''
$rules = @()
$old = ($raw | Out-String).Trim()
if ($old) {
    $document = $old | ConvertFrom-Json
    $expected = [string]$document.version
    $rules = @($document.rules)
}
$missing = @($cartRules | Where-Object { $_.id -notin $rules.id })
if ($missing.Count -eq 0) {
    Write-Output 'Cart rules already exist.'
    return
}

$next = @{version = ('cart-' + [guid]::NewGuid().ToString('N')); rules = @($rules) + $missing}
$directory = Join-Path $root 'target/cart-rules'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$file = Join-Path $directory ([guid]::NewGuid().ToString('N') + '.json')
try {
    [IO.File]::WriteAllText($file, ($next | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'Publish-DevRbac.ps1') -Type Rules -Document $file -KeyPrefix $KeyPrefix -ExpectedVersion $expected
} finally {
    Remove-Item -LiteralPath $file
}
