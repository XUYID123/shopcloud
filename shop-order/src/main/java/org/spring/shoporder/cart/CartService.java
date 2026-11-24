package org.spring.shoporder.cart;

import feign.FeignException;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.logging.BizLog;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class CartService {
    private static final int CART_LIMIT = 20;
    private final CartStore store;
    private final CartProductClient product;
    private final TransactionTemplate writes;

    CartService(CartStore store, CartProductClient product, PlatformTransactionManager transactions) {
        this.store = store;
        this.product = product;
        this.writes = new TransactionTemplate(transactions);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<CartItem> list(long userId) {
        var entries = database(() -> store.list(userId));
        if (entries.isEmpty()) return List.of();
        var metadata = metadata(entries.stream().map(CartStore.Entry::skuId).toList());
        return entries.stream().map(entry -> toItem(entry.skuId(), entry.quantity(),
                metadata.get(Long.toString(entry.skuId())))).toList();
    }

    @BizLog(action = "CART_SET_QUANTITY", bizId = "#p1")
    public CartItem put(long userId, long skuId, int quantity) {
        var sku = metadata(List.of(skuId)).get(Long.toString(skuId));
        if (sku == null) throw new BusinessException(ErrorCode.SKU_NOT_FOUND);
        database(() -> writes.execute(status -> {
            store.lockOrCreateOwner(userId);
            if (!store.contains(userId, skuId) && store.size(userId) >= CART_LIMIT) {
                throw new BusinessException(ErrorCode.CART_LIMIT_EXCEEDED);
            }
            store.put(userId, skuId, quantity);
            return null;
        }));
        return toItem(skuId, quantity, sku);
    }

    @BizLog(action = "CART_REMOVE_ITEM", bizId = "#p1")
    public void delete(long userId, long skuId) {
        database(() -> writes.execute(status -> {
            if (store.lockOwner(userId)) store.delete(userId, skuId);
            return null;
        }));
    }

    private Map<String, CartProductClient.Sku> metadata(List<Long> ids) {
        final List<CartProductClient.Sku> skus;
        try {
            var response = product.find(ids);
            if (response == null || !"OK".equals(response.getCode()) || response.getData() == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            skus = response.getData();
        } catch (FeignException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
        Set<String> requested = Set.copyOf(ids.stream().map(Object::toString).toList());
        Map<String, CartProductClient.Sku> byId = new HashMap<>();
        for (var sku : skus) {
            if (sku == null || sku.skuId() == null || !requested.contains(sku.skuId())
                    || sku.productName() == null || sku.specName() == null
                    || sku.priceFen() == null || sku.priceFen() < 0 || sku.weightGram() == null || sku.weightGram() <= 0
                    || byId.putIfAbsent(sku.skuId(), sku) != null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
        }
        return byId;
    }

    private static CartItem toItem(long skuId, int quantity, CartProductClient.Sku sku) {
        return sku == null ? new CartItem(Long.toString(skuId), quantity, null, null, null, null, false)
                : new CartItem(Long.toString(skuId), quantity, sku.productName(), sku.specName(),
                sku.priceFen(), sku.weightGram(), true);
    }

    private static <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (CannotCreateTransactionException | TransientDataAccessException
                 | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }
}
