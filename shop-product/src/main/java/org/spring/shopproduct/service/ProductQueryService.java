package org.spring.shopproduct.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopproduct.cache.ProductDetailCache;
import org.spring.shopproduct.dto.ProductListQuery;
import org.spring.shopproduct.mapper.ProductMapper;
import org.spring.shopproduct.mapper.SkuMapper;
import org.spring.shopproduct.vo.ProductCreatedVO;
import org.spring.shopproduct.vo.ProductPageVO;
import org.spring.shopproduct.vo.SkuCreatedVO;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 商城公开商品列表和详情查询。 */
@Service
public class ProductQueryService {
    private final ProductMapper products;
    private final SkuMapper skus;
    private final TransactionTemplate readTransaction;
    private final ProductDetailCache detailCache;

    public ProductQueryService(ProductMapper products, SkuMapper skus, PlatformTransactionManager transactionManager,
                               ProductDetailCache detailCache) {
        this.products = products;
        this.skus = skus;
        this.detailCache = detailCache;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 业务日志包含参数校验和数据库查询的结果，搜索词不进入日志。 */
    @BizLog(action = "PRODUCT_LIST")
    public ProductPageVO list(String categoryId, String keyword, String sort, String page, String size) {
        ProductListQuery query = ProductListQuery.parse(categoryId, keyword, sort, page, size);
        try {
            // 总数和列表使用同一个只读快照。事务开启也在此异常处理范围内。
            return readTransaction.execute(transaction -> readPage(query));
        } catch (CannotCreateTransactionException | TransientDataAccessException |
                 DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private ProductPageVO readPage(ProductListQuery query) {
        long total = products.countPublicProducts(query);
        // 先判断页码是否超出结果范围，再计算 offset，避免极大页码乘法溢出。
        if (total == 0 || query.page() > (total - 1) / query.size() + 1) {
            return new ProductPageVO(List.of(), query.page(), query.size(), total);
        }
        long offset = (query.page() - 1) * query.size();
        return new ProductPageVO(products.selectPublicProducts(query, offset), query.page(), query.size(), total);
    }

    /** 校验 ID 后先查缓存。只有回源才进入只读事务，回填在事务结束后进行。 */
    @BizLog(action = "PRODUCT_DETAIL", bizId = "#p0", level = BizLog.Level.DEBUG)
    public ProductCreatedVO detail(String id) {
        // 与写接口一致使用正整数 ID 字符串，非法格式或 Long 溢出返回 400。
        final long productId;
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            productId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        ProductCreatedVO detail = detailCache.getOrLoad(productId, () -> loadDetail(productId));
        if (detail == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return detail;
    }

    private ProductCreatedVO loadDetail(long productId) {
        try {
            return readTransaction.execute(transaction -> readDetail(productId));
        } catch (CannotCreateTransactionException | TransientDataAccessException |
                 DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    /** 商品与启用规格使用同一个可重复读快照。null 只表示商品不可见。 */
    private ProductCreatedVO readDetail(long productId) {
        var product = products.selectVisibleById(productId);
        if (product == null) {
            return null;
        }
        // 只输出启用规格，库存字段继续为 null。
        var details = skus.selectEnabledByProduct(productId).stream()
                .map(s -> new SkuCreatedVO(s.getId().toString(), s.getProductId().toString(), s.getSkuCode(),
                        s.getSpecName(), s.getWeightGram(), s.getPriceFen(), s.getEnabled(), null, s.getVersion()))
                .toList();
        return new ProductCreatedVO(product.getId().toString(), product.getCategoryId().toString(), product.getName(),
                product.getBrand(), product.getGlutenType(), product.getUsageText(), product.getIngredients(), product.getStorage(),
                product.getShelfLifeDays(), product.getImageUrl(), product.getStatus(), product.getVersion(), details);
    }
}
