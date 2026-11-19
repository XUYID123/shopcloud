package org.spring.shopproduct;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.dto.ProductCreateRequest;
import org.spring.shopproduct.entity.CategoryEntity;
import org.spring.shopproduct.mapper.*;
import org.spring.shopproduct.service.ProductService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 数据库依赖故障与程序错误的处理边界；不将未知结果写入自动重试。 */
class ProductServiceTests {
    @Test void classifiesDatabaseFailureWithoutRetryingInsert() {
        var products=mock(ProductMapper.class);var categories=mock(CategoryMapper.class);
        var category=new CategoryEntity();category.setId(1L);category.setEnabled(true);
        when(categories.selectForShare(1)).thenReturn(category);
        when(products.insert(any())).thenThrow(new DataAccessResourceFailureException("secret connection"));
        var service=new ProductService(products,categories);
        assertThatThrownBy(()->service.create(input())).isInstanceOfSatisfying(BusinessException.class,
                e->{assertThat(e.getCode()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("secret");});
        verify(products,times(1)).insert(any());
        var bug=new BadSqlGrammarException("bad sql","sql",new java.sql.SQLException());
        doThrow(bug).when(products).insert(any());
        assertThatThrownBy(()->service.create(input())).isSameAs(bug);
    }
    private ProductCreateRequest input(){return new ProductCreateRequest("1","面粉","麦香",ProductCreateRequest.GlutenType.HIGH,
            "面包","小麦","阴凉干燥处",180,"https://static.example.com/flour.jpg");}
}
