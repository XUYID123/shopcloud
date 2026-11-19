package org.spring.shopproduct;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.cache.ProductDetailCache;
import org.spring.shopproduct.mapper.*;
import org.spring.shopproduct.service.ProductQueryService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询故障不能伪装成商品不存在；未知 SQL 错误保持原始程序异常分类。 */
class ProductQueryServiceTests {
    @Test void preservesDependencyFailureAndSqlBugBoundaries() {
        var products=mock(ProductMapper.class);var skus=mock(SkuMapper.class);
        var cache = mock(ProductDetailCache.class);
        when(cache.getOrLoad(eq(1L), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        var service=new ProductQueryService(products,skus,mock(PlatformTransactionManager.class),cache);
        when(products.selectVisibleById(1)).thenThrow(new DataAccessResourceFailureException("secret connection"));
        assertThatThrownBy(()->service.detail("1")).isInstanceOfSatisfying(BusinessException.class,
                e->{assertThat(e.getCode()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("secret");});
        var bug=new BadSqlGrammarException("sql","sql",new java.sql.SQLException());doThrow(bug).when(products).selectVisibleById(1);
        assertThatThrownBy(()->service.detail("1")).isSameAs(bug);verifyNoInteractions(skus);
    }

    @Test void listReportsTransactionConnectionFailureAs503AndKeepsCause() {
        var products = mock(ProductMapper.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        var service = new ProductQueryService(products, mock(SkuMapper.class), transactionManager, mock(ProductDetailCache.class));
        var failure = new CannotCreateTransactionException("internal database address");
        when(transactionManager.getTransaction(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.list(null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getBusinessCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain(failure.getMessage());
                    assertThat(error.getCause()).isSameAs(failure);
                });
        verifyNoInteractions(products);
    }
}
