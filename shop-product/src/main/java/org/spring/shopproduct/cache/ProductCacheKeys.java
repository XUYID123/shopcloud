package org.spring.shopproduct.cache;

/** 商品详情缓存键由 shop-product 管理，花括号将商品 ID 用作 Redis 集群 hash tag。 */
public final class ProductCacheKeys {
    private ProductCacheKeys() { }

    public static String detail(long productId) {
        return "product:detail:{" + productId + "}";
    }
}
