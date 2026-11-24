# 商城容量场景

在隔离压测环境使用固定版本 k6。脚本采用 [到达率执行器](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)和独立延迟指标。 `dropped_iterations` 计入门槛，库存完成时间独立于下单受理时间。

```powershell
$env:BASE_URL = 'http://127.0.0.1:8888'
$env:PRODUCT_IDS = '2001,2002,3001'
$env:MODE = 'catalog'
$env:RATE = '2000'
$env:DURATION = '15m'
k6 run performance/shop.js
```

| 模式 | 数据和指标 |
| --- | --- |
| `catalog` | 80% SPU 详情走商品详情缓存，20% 商品列表走数据库分页。单独查看 `catalog.detail`、`catalog.list` 标签。缓存命中率从缓存服务侧确认。 |
| `order` | `FIXTURES_FILE` 指向 JSON 数组，元素含 `accessToken`、`addressId`、`items`。按计划迭代量提供测试买家和库存。每次先生成新报价，再测 POST 受理和库存最终完成。 |
| `payment` | 每个输入增加具备 Mock 权限的 `adminAccessToken`。在 dev/test 环境创建支付单并触发 Mock 成功事件，测到 `PAID / CONFIRMED` 的时间。 |
| `stock-contention` | 准备同一热点 SKU，令 `EXPECT_RESERVED` 等于可满足的订单数。逐笔检查成功预占和业务缺货，其余返回单独计为意外结果。 |

订单测试用例需要足够长的测试会话有效期，避免每个账户达到五个进行中订单上限。到达率模式的输入数量应高于计划速率乘稳态时长，为结束边界保留余量。使用独立准入阈值压测业务吞吐，单独阶段验证限流。按[实施与验收](../docs/04-实施与验收.md#4-容量验收方法)执行暖机、升压、稳态和对账，记录客户端实际启动率、两类延迟、拒绝、异常、积压和实例配置。
