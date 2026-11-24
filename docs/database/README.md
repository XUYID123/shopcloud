# 数据库

MySQL 为事实来源，user、product、inventory、order 和 pay 分别运行 Flyway，迁移版本在模块内递增。

- [user 迁移](../../shop-user/src/main/resources/db/migration)包含账号、角色、权限和两张关系表。账号名/角色码/权限码唯一，关联表使用外键和复合主键。权限失效表使用 user_id、key_prefix 去重。
- [用户地址迁移 V5](../../shop-user/src/main/resources/db/migration/V5__create_user_address.sql)为本人收货地址建立账号外键、按所有者索引及默认地址唯一约束。[事务与验证](../agent-engineering/tasks/create-shipping-address.md)
- [product 迁移](../../shop-product/src/main/resources/db/migration)包含 category → product_spu → product_sku 的外键、唯一分类名与 SKU 编码、按启用态/分类/销售态查询的索引，以及缓存失效执行队列。
- [CRM 快照迁移 V5](../../shop-product/src/main/resources/db/migration/V5__create_crm_catalog_snapshot.sql)在商品库保存带 SPU 与 SKU 的本机 CRM 目录。按内容 SHA-256 去重，每次完整写入一份快照。导入和回读见[任务记录](../agent-engineering/tasks/shop-web-catalog-import.md)。
- [inventory 迁移](../../shop-inventory/src/main/resources/db/migration/V1__create_stock_receipt_tables.sql)独立保存 SKU 汇总、批次、流水和入库幂等收据。商品元数据由库存服务走受限接口查询，库存写事务走自己的数据源。见[入库记录](../agent-engineering/tasks/inventory-batch-receipt.md)。
- [order 迁移 V1](../../shop-order/src/main/resources/db/migration/V1__create_cart_tables.sql)建立购物车所有者锁行及 `(user_id, sku_id)` 主键，写入仅保存 SKU 和数量。[购物车事务记录](../agent-engineering/tasks/cart-items.md)
- [order 迁移 V2](../../shop-order/src/main/resources/db/migration/V2__create_checkout_quote.sql)保存报价、价格摘要、商品及地址快照，通过报价 ID 唯一定位，并建立过期时间索引。[报价记录](../agent-engineering/tasks/checkout-and-orders.md)
- [order 迁移 V3](../../shop-order/src/main/resources/db/migration/V3__create_order_reservation_flow.sql)增加报价唯一消费、本人幂等键、订单游标索引及活跃订单配额。[inventory 迁移 V2](../../shop-inventory/src/main/resources/db/migration/V2__create_inventory_reservations.sql)增加预占、批次分配、释放墓碑。两个服务各自保存交易 Inbox 和 Outbox。[M3 事务](../agent-engineering/tasks/m3-order-reservation.md)
- [pay 迁移 V1](../../shop-pay/src/main/resources/db/migration/V1__create_payments.sql)建立支付控制、渠道事实、回调去重、退款和持久消息表。[order V4](../../shop-order/src/main/resources/db/migration/V4__add_order_closing.sql)持久化取消申请及退款标识，[inventory V3](../../shop-inventory/src/main/resources/db/migration/V3__bind_inventory_confirmation.sql)绑定库存确认的支付单。[M4 事务](../agent-engineering/tasks/m4-payment-close.md)
- [order V5](../../shop-order/src/main/resources/db/migration/V5__add_order_fulfillment.sql)追加确认收货时间、后台游标索引、唯一物流单、批次快照及发货幂等绑定。[M5 事务](../agent-engineering/tasks/m5-fulfillment.md)
- [商品 SQL](../../shop-product/src/main/resources/mapper)使用共享锁校验关联记录，排他锁锁定 SPU，销售态写入使用 version CAS。业务写与失效记录同事务，多表只读快照使用 REPEATABLE READ。用户注册在同事务写账号、初始角色及权限失效任务。
- 迁移按扩展、兼容应用、可重试分批回填、验证、清理推进。已执行迁移保留原校验和。以隔离 MySQL 验证空库及旧结构升级、EXPLAIN、约束失败、回滚、并发。以业务唯一键与事务写保证持久幂等，明确重复结果、锁顺序、有界重试。
- 参见[数据库与消息](../messaging/database-consistency.md)。
