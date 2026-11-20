package org.spring.shopproduct;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.cache.ProductCacheInvalidationOutbox;
import org.spring.shopproduct.dto.SkuCreateRequest;
import org.spring.shopproduct.mapper.*;
import org.spring.shopproduct.service.SkuService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证依赖故障与程序错误的边界，避免自动重试写入或泄漏连接信息。 */
class SkuServiceTests {
    @Test void detailMapsDependencyFailuresAndRetainsSqlBugs() {
        var skus = mock(SkuMapper.class);
        var service = new SkuService(mock(ProductMapper.class), skus, mock(ProductCacheInvalidationOutbox.class));
        var unavailable = new DataAccessResourceFailureException("secret connection");
        when(skus.selectVisibleById(1)).thenThrow(unavailable);
        assertThatThrownBy(() -> service.detail("1")).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(503);
            assertThat(error.getBusinessCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(error.getCause()).isSameAs(unavailable);
            assertThat(error.getMessage()).doesNotContain("secret");
        });
        var bug = new BadSqlGrammarException("sql", "sql", new java.sql.SQLException());
        doThrow(bug).when(skus).selectVisibleById(1);
        assertThatThrownBy(() -> service.detail("1")).isSameAs(bug);
    }

    @Test void dependencyFailureIs503ButSqlBugIsNotHiddenAndInsertIsNotRetried() {
        var products=mock(ProductMapper.class);var skus=mock(SkuMapper.class);
        when(products.selectIdForShare(1)).thenReturn(1L);
        when(skus.insert(any())).thenThrow(new DataAccessResourceFailureException("secret connection"));
        var service=new SkuService(products,skus,mock(ProductCacheInvalidationOutbox.class));var input=new SkuCreateRequest("1","TEST","5kg",5000L,2990L,true);
        assertThatThrownBy(()->service.create(input)).isInstanceOfSatisfying(BusinessException.class,
                e->{assertThat(e.getCode()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("secret");});
        verify(skus,times(1)).insert(any());
        var bug=new BadSqlGrammarException("sql","sql",new java.sql.SQLException());doThrow(bug).when(skus).insert(any());
        assertThatThrownBy(()->service.create(input)).isSameAs(bug);
    }
}
