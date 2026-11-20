#requires -Version 7.0
param([string]$JavaHome=$env:JAVA_HOME,[switch]$IncludeInventory)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java=Join-Path $JavaHome 'bin/java.exe'
$out=Join-Path $root 'target/category-smoke'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$resultFile=Join-Path $out 'result.json'
if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile }
$namespace='test:category:'+ [guid]::NewGuid().ToString('N')
$username='cat_'+[guid]::NewGuid().ToString('N').Substring(0,16)
$name='cat_smoke_'+[guid]::NewGuid().ToString('N').Substring(0,16)
$processes=[Collections.Generic.List[object]]::new()
$userId=$null
$inventorySkuId=$null
function Port {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop();return $port
}
# Windows 的重定向输出文件仍被后台进程持有，读取端也须允许已有写句柄。
function Read-SharedLog([string]$file) {
    $stream=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $reader=[IO.StreamReader]::new($stream)
    try {return $reader.ReadToEnd()} finally {$reader.Dispose()}
}
function Sql([string]$service,[string]$sql) {
    $file=switch ($service) {
        'mysql-user' {'shop-user/compose.yaml'}
        'mysql-inventory' {'shop-inventory/compose.yaml'}
        default {'shop-product/compose.yaml'}
    }
    $sql | & docker compose -f (Join-Path $root $file) exec -T $service sh -c `
        'exec env MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) {throw 'Test SQL failed'}
}
function Redis([string]$service,[string[]]$arguments) {
    $value=& docker compose -f (Join-Path $root 'shop-gateway/compose.yaml') exec -T $service sh -c `
        'exec env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw "$@"' category-smoke @arguments
    if ($LASTEXITCODE -ne 0) {throw 'Test Redis failed'}
    return $value
}
function Start-ServiceTest([string]$module,[string]$jar,[int]$port,[string[]]$extra) {
    $javaArguments=@('-jar',(Join-Path $root "$module/target/$jar"),"--server.port=$port",'--spring.docker.compose.enabled=false',
        '--spring.cloud.nacos.discovery.enabled=false',"--logging.file.path=$out/rolling", "--shop.internal-auth.public-key-location=$internalPublic")+$extra
    $process=Start-Process $java -ArgumentList $javaArguments -WindowStyle Hidden -PassThru -WorkingDirectory $root `
        -RedirectStandardOutput (Join-Path $out "$module.log") -RedirectStandardError (Join-Path $out "$module-error.log")
    $processes.Add($process)
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {throw "$module exited; inspect logs"}
        try {
            $response=Invoke-WebRequest "http://127.0.0.1:$port/__ready__" -SkipHttpErrorCheck -TimeoutSec 1
            if ($response.Headers.ContainsKey('X-Trace-Id')) {return}
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    throw "$module startup timeout"
}
try {
    $userPort=Port;$productPort=Port;$gatewayPort=Port
    $private=[Uri]::new((Join-Path $root 'infra/local-secrets/jwt-private.pem')).AbsoluteUri
    $public=[Uri]::new((Join-Path $root 'infra/local-secrets/jwt-public.pem')).AbsoluteUri
    $internalPrivate=[Uri]::new((Join-Path $root 'infra/local-secrets/gateway-internal-private.pem')).AbsoluteUri
    $internalPublic=[Uri]::new((Join-Path $root 'infra/local-secrets/gateway-internal-public.pem')).AbsoluteUri
    $password=if ($env:SHOP_USER_MYSQL_PASSWORD) {$env:SHOP_USER_MYSQL_PASSWORD} else {'shop_user_local_dev'}
    Start-ServiceTest 'shop-user' 'shop-user-1.0-SNAPSHOT.jar' $userPort @(
        '--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/flour_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
        '--spring.datasource.username=shop_user',"--spring.datasource.password=$password",
        "--shop.auth.private-key-location=$private","--shop.auth.public-key-location=$public",
        "--shop.auth.key-prefix=$namespace","--shop.permissions.key-prefix=$namespace")
    $productExtras=@()
    $gatewayInventory=@()
    if ($IncludeInventory) {
        $inventoryPort=Port
        $peerPrivate=[Uri]::new((Join-Path $root 'infra/local-secrets/inventory-product-private.pem')).AbsoluteUri
        $peerPublic=[Uri]::new((Join-Path $root 'infra/local-secrets/inventory-product-public.pem')).AbsoluteUri
        $productExtras=@('--shop.product.inventory-lookup.enabled=true',"--shop.product.inventory-lookup.public-key-location=$peerPublic")
        $gatewayInventory=@("--spring.cloud.discovery.client.simple.instances.shop-inventory[0].uri=http://127.0.0.1:$inventoryPort")
    }
    Start-ServiceTest 'shop-product' 'shop-product-0.0.1-SNAPSHOT.jar' $productPort $productExtras
    if ($IncludeInventory) {
        Start-ServiceTest 'shop-inventory' 'shop-inventory-1.0-SNAPSHOT.jar' $inventoryPort @(
            "--spring.cloud.discovery.client.simple.instances.shop-product[0].uri=http://127.0.0.1:$productPort",
            "--shop.inventory.signing-private-key-location=$peerPrivate")
        & (Join-Path $PSScriptRoot 'Enable-InventoryRule.ps1') -KeyPrefix $namespace | Out-Null
    }
    & (Join-Path $PSScriptRoot 'Enable-CategoryRule.ps1') -KeyPrefix $namespace | Out-Null
    & (Join-Path $PSScriptRoot 'Enable-ProductRule.ps1') -KeyPrefix $namespace | Out-Null
    & (Join-Path $PSScriptRoot 'Enable-SkuRule.ps1') -KeyPrefix $namespace | Out-Null
    & (Join-Path $PSScriptRoot 'Enable-ProductSaleRule.ps1') -KeyPrefix $namespace | Out-Null
    Start-ServiceTest 'shop-gateway' 'shop-gateway-1.0-SNAPSHOT.jar' $gatewayPort (@(
        "--spring.cloud.discovery.client.simple.instances.shop-user[0].uri=http://127.0.0.1:$userPort",
        "--spring.cloud.discovery.client.simple.instances.shop-product[0].uri=http://127.0.0.1:$productPort",
        "--gateway.auth.public-key-location=$public","--gateway.internal-jwt.private-key-location=$internalPrivate","--gateway.auth.key-prefix=$namespace",
        "--gateway.protection.anti-abuse.key-prefix=$namespace")+$gatewayInventory)
    $registered=Invoke-RestMethod "http://127.0.0.1:$gatewayPort/api/v1/users" -Method Post -ContentType 'application/json' `
        -Body (@{username=$username;password='FlourCategory123!'}|ConvertTo-Json -Compress)
    $userId=[string]$registered.data.userId
    if ($userId -notmatch '^[1-9][0-9]{0,18}$') {throw 'Invalid test user ID'}
    $login=Invoke-RestMethod "http://127.0.0.1:$gatewayPort/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{username=$username;password='FlourCategory123!'}|ConvertTo-Json -Compress)
    $url="http://127.0.0.1:$gatewayPort/api/v1/admin/categories"
    $payload=@{name=$name;sort=1;enabled=$true}|ConvertTo-Json -Compress
    $anonymous=Invoke-WebRequest $url -Method Post -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$anonymous.StatusCode -ne 401) {throw 'Anonymous was not rejected'}
    $headers=@{Authorization="Bearer $($login.data.accessToken)";'X-User-Id'='999';'X-Internal-Token'='forged'}
    if ($IncludeInventory) {
        $inventoryPath='/api/v1/admin/inventory/batches'
        $inventoryUrl="http://127.0.0.1:$gatewayPort$inventoryPath"
        $anonymousInventory=Invoke-WebRequest $inventoryUrl -Method Post -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
        $ordinaryInventory=Invoke-WebRequest $inventoryUrl -Method Post -Headers $headers -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
        if ([int]$anonymousInventory.StatusCode -ne 401 -or [int]$ordinaryInventory.StatusCode -ne 403) {throw 'Inventory permissions failed'}
    }
    $ordinary=Invoke-WebRequest $url -Method Post -Headers $headers -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$ordinary.StatusCode -ne 403) {throw 'Ordinary user could create category'}
    $productUrl="http://127.0.0.1:$gatewayPort/api/v1/admin/products"
    $ordinaryProduct=Invoke-WebRequest $productUrl -Method Post -Headers $headers -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
    if ([int]$ordinaryProduct.StatusCode -ne 403) {throw 'Ordinary user could create product'}
    $anonymousProduct=Invoke-WebRequest $productUrl -Method Post -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
    if ([int]$anonymousProduct.StatusCode -ne 401) {throw 'Anonymous could create product'}
    # 新规格接口同样要求 product:write，普通用户和匿名请求不得进入商品控制器。
    $skuUrl="http://127.0.0.1:$gatewayPort/api/v1/admin/skus"
    $ordinarySku=Invoke-WebRequest $skuUrl -Method Post -Headers $headers -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
    $anonymousSku=Invoke-WebRequest $skuUrl -Method Post -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
    if ([int]$ordinarySku.StatusCode -ne 403 -or [int]$anonymousSku.StatusCode -ne 401) {throw 'SKU access control failed'}
    # 商品是否存在不应影响网关权限拒绝：普通用户在转发之前即被拒绝。
    $ordinarySale=Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/admin/products/1/sale-status" -Method Put -Headers $headers -ContentType 'application/json' -Body '{"status":"ON_SALE","expectedVersion":1}' -SkipHttpErrorCheck
    if ([int]$ordinarySale.StatusCode -ne 403) {throw 'Ordinary user could change sale status'}
    # 只给本脚本创建的临时账号赋权，验证角色来源于数据库而不是 JWT/客户端身份头。
    Sql 'mysql-user' "INSERT INTO user_role(user_id,role_id) SELECT $userId,id FROM role WHERE role_code='ADMIN';" | Out-Null
    Redis 'redis-cache' @('DEL',($namespace+':user:{'+$userId+'}')) | Out-Null
    $trace=[guid]::NewGuid().ToString('N').Substring(0,16);$headers['X-Trace-Id']=$trace
    $created=Invoke-WebRequest $url -Method Post -Headers $headers -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$created.StatusCode -ne 201) {throw "Admin create failed: $($created.StatusCode)"}
    $document=$created.Content|ConvertFrom-Json
    if ($document.data.name -ne $name -or $created.Headers['Location'][0] -ne "/api/v1/admin/categories/$($document.data.categoryId)") {throw 'Invalid result/Location'}
    if ($document.traceId -ne $trace -or $created.Headers['X-Trace-Id'][0] -ne $trace) {throw 'Trace mismatch'}
    # 复用已验证管理员身份，绑定刚创建的分类；SPU 不接收价格、库存或销售状态。
    $productBody=@{categoryId=[string]$document.data.categoryId;name=$name;brand='smoke';glutenType='HIGH';
        usageText='面包';ingredients='小麦';storage='阴凉干燥';shelfLifeDays=180;imageUrl='https://static.example.com/flour.jpg'}
    $newProduct=Invoke-WebRequest $productUrl -Method Post -Headers $headers -ContentType 'application/json' -Body ($productBody|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$newProduct.StatusCode -ne 201) {throw "Product create failed: $($newProduct.StatusCode)"}
    $product=$newProduct.Content|ConvertFrom-Json
    if ($product.data.status -ne 'OFF_SHELF' -or $product.data.version -ne 1 -or @($product.data.skus).Count -ne 0 -or $product.traceId -ne $trace) {throw 'Invalid product defaults/trace'}
    if ($newProduct.Headers['Location'][0] -ne "/api/v1/admin/products/$($product.data.productId)") {throw 'Invalid product Location'}
    # 使用刚创建的 SPU 创建规格，验证真实网关鉴权、内部 JWT 和数据库唯一冲突。
    $skuBody=@{productId=[string]$product.data.productId;skuCode=$name;specName='5kg袋装';weightGram=5000;priceFen=2990;enabled=$true}|ConvertTo-Json -Compress
    $newSku=Invoke-WebRequest $skuUrl -Method Post -Headers $headers -ContentType 'application/json' -Body $skuBody -SkipHttpErrorCheck
    if ([int]$newSku.StatusCode -ne 201) {throw "SKU create failed: $($newSku.StatusCode)"}
    $sku=$newSku.Content|ConvertFrom-Json
    if ($sku.data.productId -ne $product.data.productId -or $sku.data.version -ne 1 -or $null -ne $sku.data.estimatedAvailable -or $sku.traceId -ne $trace) {throw 'SKU response invalid'}
    if ($newSku.Headers['Location'][0] -ne "/api/v1/admin/skus/$($sku.data.skuId)") {throw 'SKU Location invalid'}
    $duplicateSku=Invoke-WebRequest $skuUrl -Method Post -Headers $headers -ContentType 'application/json' -Body $skuBody -SkipHttpErrorCheck
    if ([int]$duplicateSku.StatusCode -ne 409 -or ($duplicateSku.Content|ConvertFrom-Json).code -ne 'SKU_CODE_EXISTS') {throw 'SKU duplicate not rejected'}
    $directSku=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/admin/skus" -Method Post -Headers $headers -ContentType 'application/json' -Body $skuBody -SkipHttpErrorCheck
    if ([int]$directSku.StatusCode -ne 401) {throw 'SKU direct bypass accepted'}
    if ($IncludeInventory) {
        $inventorySkuId=[string]$sku.data.skuId
        if ($inventorySkuId -notmatch '^[1-9][0-9]{0,18}$') {throw 'Invalid inventory fixture SKU'}
        $stockTrace=[guid]::NewGuid().ToString('N').Substring(0,16)
        $stockHeaders=$headers.Clone();$stockHeaders['X-Trace-Id']=$stockTrace;$stockHeaders['Idempotency-Key']=[guid]::NewGuid().ToString()
        $today=[DateTime]::UtcNow.AddHours(8).Date
        $stockBody=@{skuId=$inventorySkuId;warehouseId='WH-SH-01';batchNo=$name;
            manufacturedOn=$today.AddDays(-1).ToString('yyyy-MM-dd');expiresOn=$today.AddDays(90).ToString('yyyy-MM-dd');quantity=100}|ConvertTo-Json -Compress
        # 此时 SPU 仍未上架，库存服务通过独立身份读取商品保质期。
        $stock=Invoke-WebRequest $inventoryUrl -Method Post -Headers $stockHeaders -ContentType 'application/json' -Body $stockBody -SkipHttpErrorCheck
        $receipt=$stock.Content|ConvertFrom-Json
        if ([int]$stock.StatusCode -ne 201 -or $receipt.data.skuId -ne $inventorySkuId -or $receipt.data.available -ne 100 -or
            $receipt.traceId -ne $stockTrace -or $stock.Headers['X-Trace-Id'][0] -ne $stockTrace) {throw "Stock receipt failed: HTTP $($stock.StatusCode) code=$($receipt.code)"}
        $replay=Invoke-WebRequest $inventoryUrl -Method Post -Headers $stockHeaders -ContentType 'application/json' -Body $stockBody -SkipHttpErrorCheck
        if ([int]$replay.StatusCode -ne 200 -or ($replay.Content|ConvertFrom-Json).data.batchId -ne $receipt.data.batchId) {throw 'Inventory receipt replay failed'}
        $direct=Invoke-WebRequest "http://127.0.0.1:$inventoryPort$inventoryPath" -Method Post -Headers $stockHeaders -ContentType 'application/json' -Body $stockBody -SkipHttpErrorCheck
        $directMetadata=Invoke-WebRequest "http://127.0.0.1:$productPort/internal/v1/products/skus/$inventorySkuId/stock-metadata" -SkipHttpErrorCheck
        if ([int]$direct.StatusCode -ne 401 -or [int]$directMetadata.StatusCode -ne 401) {throw 'Direct call bypassed trusted identity'}
        $balance=Sql 'mysql-inventory' "SELECT available FROM sku_stock WHERE sku_id=$inventorySkuId AND warehouse_id='WH-SH-01';"
        if ($balance -ne '100') {throw 'Inventory replay changed the SKU stock'}
    }
    # 完整商品生命周期：上架、旧版本冲突、下架；认证失败请求不能进入下游。
    $saleUrl="http://127.0.0.1:$gatewayPort/api/v1/admin/products/$($product.data.productId)/sale-status"
    $saleBody=@{status='ON_SALE';expectedVersion=1}|ConvertTo-Json -Compress
    $anonymousSale=Invoke-WebRequest $saleUrl -Method Put -ContentType 'application/json' -Body $saleBody -SkipHttpErrorCheck
    if ([int]$anonymousSale.StatusCode -ne 401) {throw 'Anonymous sale change accepted'}
    $onSale=Invoke-WebRequest $saleUrl -Method Put -Headers $headers -ContentType 'application/json' -Body $saleBody -SkipHttpErrorCheck
    if ([int]$onSale.StatusCode -ne 200) {throw "On sale failed: $($onSale.StatusCode)"}
    $on=$onSale.Content|ConvertFrom-Json
    if ($on.data.status -ne 'ON_SALE' -or $on.data.version -ne 2 -or @($on.data.skus).Count -ne 1 -or $on.traceId -ne $trace) {throw 'Invalid on-sale detail/version/trace'}
    $skuDetailPath="/api/v1/skus/$($sku.data.skuId)"
    $skuDetailUrl="http://127.0.0.1:$gatewayPort$skuDetailPath"
    $skuDetailTrace=[guid]::NewGuid().ToString('N').Substring(0,16)
    $publicSkuResponse=Invoke-WebRequest $skuDetailUrl -Headers @{'X-Trace-Id'=$skuDetailTrace} -SkipHttpErrorCheck
    $publicSku=$publicSkuResponse.Content|ConvertFrom-Json
    if ([int]$publicSkuResponse.StatusCode -ne 200 -or $publicSku.code -ne 'OK' -or
        $publicSku.data.skuId -ne $sku.data.skuId -or $publicSku.data.priceFen -ne $sku.data.priceFen -or
        !$publicSku.data.enabled -or $null -ne $publicSku.data.estimatedAvailable -or
        $publicSku.traceId -ne $skuDetailTrace -or $publicSkuResponse.Headers['X-Trace-Id'][0] -ne $skuDetailTrace) {throw 'Anonymous SKU detail failed'}
    $invalidPublicSku=Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/skus/0" -SkipHttpErrorCheck
    if ([int]$invalidPublicSku.StatusCode -ne 400 -or ($invalidPublicSku.Content|ConvertFrom-Json).code -ne 'VALIDATION_ERROR') {throw 'Invalid SKU ID accepted'}
    $skuExtraPath=Invoke-WebRequest "$skuDetailUrl/extra" -SkipHttpErrorCheck
    $skuPost=Invoke-WebRequest $skuDetailUrl -Method Post -SkipHttpErrorCheck
    if ([int]$skuExtraPath.StatusCode -ne 401 -or [int]$skuPost.StatusCode -ne 401) {throw 'SKU whitelist exceeded the intended method or path'}
    $directPublicSku=Invoke-WebRequest "http://127.0.0.1:$productPort$skuDetailPath" -SkipHttpErrorCheck
    if ([int]$directPublicSku.StatusCode -ne 401 -or ($directPublicSku.Content|ConvertFrom-Json).code -ne 'INVALID_INTERNAL_TOKEN') {throw 'SKU detail bypassed internal JWT'}
    # 不携带 Access Token 或外部身份头，验证白名单仍经网关签发匿名内部 JWT。
    $detailUrl="http://127.0.0.1:$gatewayPort/api/v1/products/$($product.data.productId)"
    $detailResponse=Invoke-WebRequest $detailUrl -Headers @{'X-Trace-Id'=$trace} -SkipHttpErrorCheck
    $detail=$detailResponse.Content|ConvertFrom-Json
    if ([int]$detailResponse.StatusCode -ne 200 -or $detail.code -ne 'OK' -or $detail.data.productId -ne $product.data.productId -or @($detail.data.skus).Count -ne 1 -or $detail.traceId -ne $trace -or $detailResponse.Headers['X-Trace-Id'][0] -ne $trace) {throw 'Anonymous product detail failed'}
    $directDetail=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/products/$($product.data.productId)" -SkipHttpErrorCheck
    if ([int]$directDetail.StatusCode -ne 401) {throw 'Public product detail bypassed internal JWT'}
    # 带查询串经网关匿名转发，同时验证签名绑定、列表数据和 Trace ID。
    $publicProductsUrl="http://127.0.0.1:$gatewayPort/api/v1/products?categoryId=$($document.data.categoryId)&keyword=$name&sort=PRICE_ASC&page=1&size=5"
    $listTrace=[guid]::NewGuid().ToString('N').Substring(0,16)
    $publicProductsResponse=Invoke-WebRequest $publicProductsUrl -Headers @{'X-Trace-Id'=$listTrace} -SkipHttpErrorCheck
    $publicProducts=$publicProductsResponse.Content|ConvertFrom-Json
    if ([int]$publicProductsResponse.StatusCode -ne 200 -or $publicProducts.code -ne 'OK' -or
        $publicProducts.data.total -ne 1 -or @($publicProducts.data.items).Count -ne 1 -or
        $publicProducts.data.items[0].productId -ne $product.data.productId -or
        $publicProducts.data.items[0].minPriceFen -ne $on.data.skus[0].priceFen -or
        $publicProducts.traceId -ne $listTrace -or $publicProductsResponse.Headers['X-Trace-Id'][0] -ne $listTrace) {throw 'Anonymous product list failed'}
    $rejectedListTrace=[guid]::NewGuid().ToString('N').Substring(0,16)
    $rejectedListResponse=Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/products?page=0" -Headers @{'X-Trace-Id'=$rejectedListTrace} -SkipHttpErrorCheck
    $rejectedList=$rejectedListResponse.Content|ConvertFrom-Json
    if ([int]$rejectedListResponse.StatusCode -ne 400 -or $rejectedList.code -ne 'VALIDATION_ERROR' -or
        $rejectedList.traceId -ne $rejectedListTrace) {throw 'Invalid product list query was not rejected'}
    $staleSale=Invoke-WebRequest $saleUrl -Method Put -Headers $headers -ContentType 'application/json' -Body $saleBody -SkipHttpErrorCheck
    if ([int]$staleSale.StatusCode -ne 409 -or ($staleSale.Content|ConvertFrom-Json).code -ne 'STATE_CONFLICT') {throw 'Stale sale version accepted'}
    $offSale=Invoke-WebRequest $saleUrl -Method Put -Headers $headers -ContentType 'application/json' -Body (@{status='OFF_SHELF';expectedVersion=2}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    $off=$offSale.Content|ConvertFrom-Json
    if ([int]$offSale.StatusCode -ne 200 -or $off.data.status -ne 'OFF_SHELF' -or $off.data.version -ne 3) {throw 'Off shelf failed'}
    $hiddenSkuTrace=[guid]::NewGuid().ToString('N').Substring(0,16)
    $hiddenSku=Invoke-WebRequest $skuDetailUrl -Headers @{'X-Trace-Id'=$hiddenSkuTrace} -SkipHttpErrorCheck
    if ([int]$hiddenSku.StatusCode -ne 404 -or ($hiddenSku.Content|ConvertFrom-Json).code -ne 'SKU_NOT_FOUND') {throw 'Off-shelf SKU detail leaked'}
    # 详情现在通过持久化 Outbox 异步失效。等待已提交的第一条删除任务生效。
    for ($attempt=0; $attempt -lt 25; $attempt++) {
        $hiddenDetail=Invoke-WebRequest $detailUrl -SkipHttpErrorCheck
        if ([int]$hiddenDetail.StatusCode -eq 404) {break}
        if ([int]$hiddenDetail.StatusCode -ne 200) {throw "Unexpected detail response: $($hiddenDetail.StatusCode)"}
        Start-Sleep -Milliseconds 200
    }
    if ([int]$hiddenDetail.StatusCode -ne 404 -or ($hiddenDetail.Content|ConvertFrom-Json).code -ne 'PRODUCT_NOT_FOUND') {throw 'Off-shelf product detail leaked'}
    $hiddenProductList=Invoke-RestMethod $publicProductsUrl
    if ($hiddenProductList.code -ne 'OK' -or $hiddenProductList.data.total -ne 0 -or
        @($hiddenProductList.data.items).Count -ne 0) {throw 'Off-shelf product leaked in list'}
    $directSale=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/admin/products/$($product.data.productId)/sale-status" -Method Put -Headers $headers -ContentType 'application/json' -Body $saleBody -SkipHttpErrorCheck
    if ([int]$directSale.StatusCode -ne 401) {throw 'Sale direct bypass accepted'}
    $productBody.status='ON_SALE'
    $injected=Invoke-WebRequest $productUrl -Method Post -Headers $headers -ContentType 'application/json' -Body ($productBody|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$injected.StatusCode -ne 400) {throw 'Product status injection was accepted'}
    $directProduct=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/admin/products" -Method Post -Headers $headers -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
    if ([int]$directProduct.StatusCode -ne 401) {throw 'Product direct bypass was accepted'}
    # 不携带外部 Token，走网关匿名白名单，再由商品服务验证内部匿名 JWT。
    $listResponse=Invoke-WebRequest "http://127.0.0.1:$gatewayPort/api/v1/categories" -Headers @{'X-Trace-Id'=$trace} -SkipHttpErrorCheck
    $list=$listResponse.Content|ConvertFrom-Json
    if ([int]$listResponse.StatusCode -ne 200 -or $list.code -ne 'OK' -or $list.traceId -ne $trace) {throw 'Public category list failed'}
    $listed=@($list.data|Where-Object {$_.categoryId -eq $document.data.categoryId})
    if ($listed.Count -ne 1 -or $listed[0].name -ne $name -or @($listed[0].PSObject.Properties).Count -ne 3) {throw 'New category missing or list leaks fields'}
    $directList=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/categories" -SkipHttpErrorCheck
    if ([int]$directList.StatusCode -ne 401) {throw 'Public list bypassed internal authentication'}
    $duplicate=Invoke-WebRequest $url -Method Post -Headers $headers -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$duplicate.StatusCode -ne 409 -or ($duplicate.Content|ConvertFrom-Json).code -ne 'CATEGORY_NAME_EXISTS') {throw 'Duplicate was not 409'}
    $alias=Invoke-WebRequest "http://127.0.0.1:$gatewayPort/shop-product/api/v1/admin/categories" -Method Post -Headers $headers -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$alias.StatusCode -ne 409) {throw 'Rewritten discovery path did not preserve JWT binding'}
    $invalid=Invoke-WebRequest $url -Method Post -Headers $headers -ContentType 'application/json' `
        -Body (@{name=$name;sort=-1;enabled=$true}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$invalid.StatusCode -ne 400) {throw 'Invalid input was not 400'}
    $direct=Invoke-WebRequest "http://127.0.0.1:$productPort/api/v1/admin/categories" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body $payload -SkipHttpErrorCheck
    if ([int]$direct.StatusCode -ne 401) {throw 'Direct service bypassed gateway'}
    # 检查真实请求的日志关联：业务结果、两端访问日志均带同一个 Trace ID，查询串和认证头不写日志。
    $escapedTrace=[regex]::Escape($trace)
    # 异步日志队列由后台线程输出，给定有界等待，不把入队误当成磁盘已经可读。
    $logsReady=$false
    for($attempt=0;$attempt -lt 30;$attempt++) {
        $productLog=Read-SharedLog (Join-Path $out 'shop-product.log')
        $gatewayLog=Read-SharedLog (Join-Path $out 'shop-gateway.log')
        $userLog=Read-SharedLog (Join-Path $out 'shop-user.log')
        if ($productLog -match ($escapedTrace+'.*event=BIZ_OPERATION action=PRODUCT_SALE_STATUS_CHANGE.*outcome=SUCCESS') -and
            $productLog -match ($escapedTrace+'.*event=HTTP_ACCESS') -and
            $gatewayLog -match ($escapedTrace+'.*event=HTTP_ACCESS') -and
            $userLog -match 'event=BIZ_OPERATION action=AUTH_LOGIN.*outcome=SUCCESS' -and
            $userLog -match ($escapedTrace+'.*event=HTTP_ACCESS') -and
            $productLog -match 'event=HTTP_ACCESS.*status=201 code=OK' -and
            $productLog -match 'event=HTTP_ACCESS.*status=400 code=VALIDATION_ERROR' -and
            $productLog -match 'event=HTTP_ACCESS.*status=401 code=INVALID_INTERNAL_TOKEN' -and
            $productLog -match 'event=HTTP_ACCESS.*status=404 code=PRODUCT_NOT_FOUND' -and
            $productLog -match 'event=HTTP_ACCESS.*status=409 code=SKU_CODE_EXISTS' -and
            $productLog -match ($listTrace+'.*event=BIZ_OPERATION action=PRODUCT_LIST.*outcome=SUCCESS') -and
            $productLog -match ($rejectedListTrace+'.*event=BIZ_OPERATION action=PRODUCT_LIST.*outcome=REJECTED code=VALIDATION_ERROR') -and
            $productLog -match ($listTrace+'.*event=HTTP_ACCESS method=GET path=/api/v1/products status=200 code=OK') -and
            $productLog -match ($rejectedListTrace+'.*event=HTTP_ACCESS method=GET path=/api/v1/products status=400 code=VALIDATION_ERROR') -and
            $gatewayLog -match ($listTrace+'.*event=HTTP_ACCESS method=GET path=/api/v1/products status=200') -and
            $gatewayLog -match ($rejectedListTrace+'.*event=HTTP_ACCESS method=GET path=/api/v1/products status=400') -and
            $productLog -match ($skuDetailTrace+'.*event=BIZ_OPERATION action=SKU_DETAIL.*outcome=SUCCESS') -and
            $productLog -match ($hiddenSkuTrace+'.*event=BIZ_OPERATION action=SKU_DETAIL.*outcome=REJECTED code=SKU_NOT_FOUND') -and
            $productLog -match ($skuDetailTrace+'.*event=HTTP_ACCESS method=GET path='+$skuDetailPath+' status=200 code=OK') -and
            $productLog -match ($hiddenSkuTrace+'.*event=HTTP_ACCESS method=GET path='+$skuDetailPath+' status=404 code=SKU_NOT_FOUND') -and
            $gatewayLog -match ($skuDetailTrace+'.*event=HTTP_ACCESS method=GET path='+$skuDetailPath+' status=200') -and
            $gatewayLog -match ($hiddenSkuTrace+'.*event=HTTP_ACCESS method=GET path='+$skuDetailPath+' status=404') -and
            $gatewayLog -match 'event=HTTP_ACCESS.*status=401 code=UNAUTHENTICATED' -and
            $gatewayLog -match 'event=HTTP_ACCESS.*status=403 code=FORBIDDEN') {$logsReady=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if (!$logsReady) {throw 'Request/business trace logging missing'}
    if ($productLog -match ($listTrace+'.*'+[regex]::Escape($name)) -or
        $gatewayLog -match ($listTrace+'.*'+[regex]::Escape($name))) {throw 'Search keyword leaked into product list logs'}
    foreach($logText in @($productLog,$gatewayLog,$userLog)) {
        if ($logText.Contains([string]$login.data.accessToken) -or $logText.Contains([string]$login.data.refreshToken) -or $logText.Contains('FlourCategory123!')) {throw 'Credential leaked into logs'}
    }
    $report=@{passed=$true;realUserLogin=$true;anonymous401=$true;ordinary403=$true;admin201=$true;duplicate409=$true;
        invalid400=$true;directBypass401=$true;traceAndLocation=$true;internalJwtDiscoveryAlias=$true;
        anonymousList200=$true;createdCategoryListed=$true;listDirectBypass401=$true;
        productAdmin201=$true;productOrdinary403=$true;productAnonymous401=$true;productStatusInjection400=$true;productDirect401=$true;
        skuAdmin201=$true;skuOrdinary403=$true;skuAnonymous401=$true;skuDuplicate409=$true;skuDirect401=$true;
        saleAdmin200=$true;saleOrdinary403=$true;saleAnonymous401=$true;saleStale409=$true;saleOffShelf200=$true;saleDirect401=$true;
        productDetailAnonymous200=$true;productDetailOffShelf404=$true;productDetailDirect401=$true;
        productListAnonymous200=$true;productListOffShelfHidden=$true;productListLogsVerified=$true;
        skuDetailAnonymous200=$true;skuDetailOffShelf404=$true;skuDetailDirect401=$true;skuDetailLogsVerified=$true;
        accessAndBusinessTraceLogs=$true;accessTokenAbsentFromLogs=$true;resultCodesInAccessLogs=$true}
    if ($IncludeInventory) {
        $inventoryLog=Read-SharedLog (Join-Path $out 'shop-inventory.log')
        if ($inventoryLog -notmatch ($stockTrace+'.*event=BIZ_OPERATION action=STOCK_BATCH_RECEIVE bizId='+$receipt.data.batchId+' userId='+$userId+' outcome=SUCCESS') -or
            $inventoryLog -notmatch ($stockTrace+'.*event=HTTP_ACCESS method=POST path='+$inventoryPath+' status=201') -or
            $productLog -notmatch ($stockTrace+'.*event=HTTP_ACCESS method=GET path=/internal/v1/products/skus/'+$inventorySkuId+'/stock-metadata status=200') -or
            $gatewayLog -notmatch ($stockTrace+'.*event=HTTP_ACCESS method=POST path='+$inventoryPath+' status=201')) {throw 'Inventory trace logs incomplete'}
        $report.inventoryReceipt201=$true
        $report.inventoryReplay200=$true
        $report.inventoryPermissionsVerified=$true
        $report.inventoryTraceLogsVerified=$true
    }
    $report=$report|ConvertTo-Json
    [IO.File]::WriteAllText($resultFile,$report,[Text.UTF8Encoding]::new($false));Write-Output $report
} finally {
    foreach ($process in $processes) {if (!$process.HasExited) {Stop-Process -Id $process.Id -Force}}
    if ($inventorySkuId -match '^[1-9][0-9]{0,18}$') {
        Sql 'mysql-inventory' "DELETE r FROM stock_batch_receipt r JOIN stock_batch b ON b.batch_id=r.batch_id WHERE b.sku_id=$inventorySkuId AND b.batch_no='$name'; DELETE l FROM stock_ledger l JOIN stock_batch b ON b.batch_id=l.batch_id WHERE b.sku_id=$inventorySkuId AND b.batch_no='$name'; DELETE FROM stock_batch WHERE sku_id=$inventorySkuId AND batch_no='$name'; DELETE FROM sku_stock WHERE sku_id=$inventorySkuId AND NOT EXISTS(SELECT 1 FROM stock_batch WHERE sku_id=$inventorySkuId);" | Out-Null
    }
    Sql 'mysql-product' "DELETE s FROM product_sku s JOIN product_spu p ON p.id=s.product_id JOIN category c ON c.id=p.category_id WHERE c.name='$name'; DELETE p FROM product_spu p JOIN category c ON c.id=p.category_id WHERE c.name='$name'; DELETE FROM category WHERE name='$name';" | Out-Null
    if ($userId -match '^[1-9][0-9]{0,18}$') {
        Sql 'mysql-user' "DELETE FROM permission_cache_invalidation WHERE user_id=$userId AND key_prefix='$namespace'; DELETE FROM user_role WHERE user_id=$userId; DELETE FROM user_account WHERE id=$userId AND username='$username';" | Out-Null
    }
    foreach ($service in @('redis-cache','redis-gateway')) {
        $keys=@(Redis $service @('--scan','--pattern',($namespace+':*'))|Where-Object {$_})
        if ($keys.Count -gt 0) {Redis $service (@('DEL')+$keys)|Out-Null}
    }
}
