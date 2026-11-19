package org.spring.shopproduct;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.cache.ProductCacheInvalidationOutbox;
import org.spring.shopproduct.dto.ProductSaleStatusRequest;
import org.spring.shopproduct.entity.ProductEntity;
import org.spring.shopproduct.mapper.*;
import org.spring.shopproduct.service.ProductSaleService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 故障边界：CAS 未命中不会报告成功，依赖故障返回 503，SQL 程序错误不被伪装。 */
class ProductSaleServiceTests {
    @Test void handlesCasMissAndDependencyFailureWithoutRetry() {
        var products=mock(ProductMapper.class);var categories=mock(CategoryMapper.class);var skus=mock(SkuMapper.class);
        var p=new ProductEntity();p.setId(1L);p.setVersion(1L);when(products.selectForUpdate(1)).thenReturn(p);
        when(skus.selectByProductForShare(1)).thenReturn(List.of());
        var service=new ProductSaleService(products,categories,skus,mock(ProductCacheInvalidationOutbox.class));
        var input=new ProductSaleStatusRequest(ProductSaleStatusRequest.SaleStatus.OFF_SHELF,1L);
        assertThatThrownBy(()->service.changeStatus("1",input)).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getBusinessCode()).isEqualTo("STATE_CONFLICT"));
        verify(products,times(1)).updateSaleStatus(1,"OFF_SHELF",1);
        doThrow(new DataAccessResourceFailureException("secret connection")).when(products).selectForUpdate(1);
        assertThatThrownBy(()->service.changeStatus("1",input)).isInstanceOfSatisfying(BusinessException.class,
                e->{assertThat(e.getCode()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("secret");});
        var bug=new BadSqlGrammarException("sql","sql",new java.sql.SQLException());doThrow(bug).when(products).selectForUpdate(1);
        assertThatThrownBy(()->service.changeStatus("1",input)).isSameAs(bug);
    }
}
