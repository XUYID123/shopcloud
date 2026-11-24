# 一单面粉 · 事实表

论点：拿到订单号只是受理，只有库存、付款和履约逐一确认，这袋面粉才会真正发出。

范围为 2026-11-24 检阅的 `main` 工作树（HEAD `6b3c48a`，包含未提交实现）。入口与状态根据源码核实，属动画示意。当前网页和后端 API 是两个不同的实现进度，动画未向任何服务发送交易请求。

| 概念 | 要看懂什么 | 容易卡在哪 | 源码依据与把握 |
|---|---|---|---|
| 前端入口 | 买家能浏览、登录、维护本地购物袋和核对清单 | 见到结算按钮就以为能提交后台 | 已核实 [app.js](../../shop-web/public/app.js) 的 `loadCart`、`saveCart`、`showCart`。后端另有 [CartController](../../shop-order/src/main/java/org/spring/shoporder/cart/CartController.java)，购物车在 shop-order |
| 报价 | 已认证用户提交地址与 SKU 数量，服务端查本人地址与配送区、实时商品数据，保存快照 | 前台预估、报价有效和占住库存是三个概念 | 已核实 [CheckoutQuoteController](../../shop-order/src/main/java/org/spring/shoporder/checkout/CheckoutQuoteController.java)、[CheckoutQuoteService](../../shop-order/src/main/java/org/spring/shoporder/checkout/CheckoutQuoteService.java)、[商品报价](../../shop-product/src/main/java/org/spring/shopproduct/controller/ProductQuoteController.java) 与 [地址查询](../../shop-user/src/main/java/org/spring/shopuser/controller/OrderAddressController.java)。有效期 5 分钟。基础运费 10 元，超过 20 kg 的部分每开始 10 kg 加 5 元。示例为 2 × 5 kg、2 × 30 元，60 + 10 = 70 元 |
| 受理与重试 | 重核报价，订单、消费报价和事务 Outbox 原子写入 | 预占消息是事务后异步投递，响应先为 PREPARING | 已核实 [OrderController](../../shop-order/src/main/java/org/spring/shoporder/order/OrderController.java)、[OrderService](../../shop-order/src/main/java/org/spring/shoporder/order/OrderService.java)、[OrderStore](../../shop-order/src/main/java/org/spring/shoporder/order/OrderStore.java)。同用户、同幂等键和同报价重试返回已有订单；同键换报价冲突。过期、已使用、版本变更分别拒绝。本人 GET 可查状态 |
| 占货与回执 | 只从可售、未过期批次扣可售量、加预留量，最早过期优先 | 仓库留货后还要回执，订单才变未支付 | 已核实 [订单消息](../../shop-order/src/main/java/org/spring/shoporder/messaging/OrderM3Messaging.java)、[库存消费](../../shop-inventory/src/main/java/org/spring/shopinventory/reservation/InventoryM3Messaging.java)、[库存事务](../../shop-inventory/src/main/java/org/spring/shopinventory/reservation/ReservationCommands.java)、[批次与余额 SQL](../../shop-inventory/src/main/java/org/spring/shopinventory/reservation/ReservationStore.java) 与 [库存回执](../../shop-order/src/main/java/org/spring/shoporder/order/OrderReservationResults.java)。Inbox 处理重复消息；预占成功后 15 分钟支付时限，并排队清理对应服务端购物车项；缺货/批次不可用走 FAILED。库存及日期为示例 |
| Mock 支付 | 待支付单建会话，签名回调验证订单、金额和币种并发布成功事件 | 付款到账和确认库存分两步 | 已核实 [PaymentController](../../shop-pay/src/main/java/org/spring/shoppay/payment/PaymentController.java)、[PaymentService](../../shop-pay/src/main/java/org/spring/shoppay/payment/PaymentService.java)、[OrderPaymentLifecycle](../../shop-order/src/main/java/org/spring/shoporder/order/OrderPaymentLifecycle.java)。订单消费支付成功后置 PAID，再发布库存确认；确认将 reserved 转 committed 并由回执置 CONFIRMED |
| 关单与退款 | 取消或超时进 CLOSING，支付确认关闭后 CLOSED，再释放预留 | 关单同时有付款结果，不能直接释放或发货 | 已核实 [OrderPaymentLifecycle](../../shop-order/src/main/java/org/spring/shoporder/order/OrderPaymentLifecycle.java)、[PaymentService](../../shop-pay/src/main/java/org/spring/shoppay/payment/PaymentService.java)、[ReservationStore](../../shop-inventory/src/main/java/org/spring/shopinventory/reservation/ReservationStore.java)。仍可售才退回可售，否则进不可售；确认关闭后迟到的成功支付发起全额退款，订单仍关闭。库存确认被拒也有退款分支 |
| 履约 | 管理员核对已付款、库存已确认的订单，通过检查预留批次数量、质量及效期登记运单 | 仅支付成功还不够；签收归买家本人 | 已核实 [FulfillmentController](../../shop-order/src/main/java/org/spring/shoporder/order/FulfillmentController.java)、[FulfillmentService](../../shop-order/src/main/java/org/spring/shoporder/order/FulfillmentService.java)。发货状态 SHIPPED，随后本人 `PUT /api/v1/orders/{orderNo}/receipt` 为 COMPLETED，重复签收返回已有结果 |
| 开关 | 报价、库存、支付和履约需要对应配置启用 | 已实现和当前环境可调用是两件事 | 已核实 [订单配置](../../shop-order/src/main/resources/application.properties)、[支付配置](../../shop-pay/src/main/resources/application.properties)、[库存配置](../../shop-inventory/src/main/resources/application.properties) 和 [部署记录](../../docs/agent-engineering/tasks/m5-fulfillment.md) |

工程路由 ROUTE-02，读取源码并制作演示。MySQL、Redis、RocketMQ 不需要为本次演示启动或修改。已启用时的下单配额、依赖熔断、定时重试等运行细节见 [M6 记录](../../docs/agent-engineering/tasks/m6-stability.md)。

## 本机复现

在仓库根目录运行以下命令。使用本机 Chrome、Playwright、imageio-ffmpeg，以及技能引擎的 NumPy、SciPy 和 Pillow 依赖。片中使用 Windows 的宋体、微软雅黑和 Consolas。

```powershell
$vibeEngine = 'C:/Users/xuyidong/.codex/skills/vibe-knowledge-film/engine'
python -X utf8 "$vibeEngine/render.py" media/order-flow --det
python -X utf8 media/order-flow/render_film.py "$vibeEngine" --jobs 4
```

完整视频输出到 `renders/final.mp4`。在第二条 Python 命令后加 `--head` 可单独生成 30 秒开场。
