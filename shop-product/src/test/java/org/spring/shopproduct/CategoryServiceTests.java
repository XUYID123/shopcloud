package org.spring.shopproduct;

import org.junit.jupiter.api.Test;
import org.spring.shopproduct.dto.CategoryCreateRequest;
import org.spring.shopproduct.mapper.CategoryMapper;
import org.spring.shopproduct.service.CategoryService;
import org.spring.shopcommon.exception.BusinessException;
import org.springframework.dao.*;
import org.springframework.jdbc.BadSqlGrammarException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

/** 明确数据库故障分类，避免把 SQL 程序错误全部伪装为可重试的依赖故障。 */
class CategoryServiceTests {
    @Test void emptyListIsSerializedAsArrayInsteadOfNull() {
        var mapper = mock(CategoryMapper.class);
        when(mapper.selectEnabled()).thenReturn(java.util.List.of());
        var result = new org.spring.shopproduct.controller.CategoryController(new CategoryService(mapper)).list();
        var data = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result).path("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isZero();
    }

    @Test void listDependencyFailureIs503AndSqlBugIsNotHiddenAsEmptyList() {
        var mapper = mock(CategoryMapper.class); var service = new CategoryService(mapper);
        when(mapper.selectEnabled()).thenThrow(new DataAccessResourceFailureException("connection secret"));
        assertThatThrownBy(service::listEnabled).isInstanceOfSatisfying(BusinessException.class,
                error -> { assertThat(error.getCode()).isEqualTo(503); assertThat(error.getMessage()).doesNotContain("secret"); });
        var bug = new BadSqlGrammarException("invalid SQL", "sql", new java.sql.SQLException());
        doThrow(bug).when(mapper).selectEnabled();
        assertThatThrownBy(service::listEnabled).isSameAs(bug);
    }

    @Test void dependencyFailureIs503ButSqlBugRemainsUnexpected() {
        var mapper = mock(CategoryMapper.class); var service = new CategoryService(mapper);
        when(mapper.insert(any())).thenThrow(new DataAccessResourceFailureException("connection secret"));
        assertThatThrownBy(() -> service.create(input())).isInstanceOfSatisfying(BusinessException.class,
                error -> { assertThat(error.getCode()).isEqualTo(503); assertThat(error.getMessage()).doesNotContain("secret"); });
        var bug = new BadSqlGrammarException("invalid SQL", "sql", new java.sql.SQLException());
        doThrow(bug).when(mapper).insert(any());
        assertThatThrownBy(() -> service.create(input())).isSameAs(bug);
    }
    private CategoryCreateRequest input() {
        var dto = new CategoryCreateRequest(); dto.setName("高筋面粉"); dto.setSort(0); dto.setEnabled(true); return dto;
    }
}
