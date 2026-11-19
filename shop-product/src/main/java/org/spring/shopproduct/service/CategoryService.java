package org.spring.shopproduct.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopproduct.dto.CategoryCreateRequest;
import org.spring.shopproduct.entity.CategoryEntity;
import org.spring.shopproduct.mapper.CategoryMapper;
import org.spring.shopproduct.vo.CategoryVO;
import org.spring.shopproduct.vo.CategoryListItemVO;
import java.util.List;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;

/** 分类创建和公开列表查询用例；复用数据库约束和索引，本阶段不增加缓存。 */
@Service
public class CategoryService {
    private final CategoryMapper mapper;
    public CategoryService(CategoryMapper mapper) { this.mapper = mapper; }

    /** 匿名查询已在网关和内部过滤器完成用途校验；这里只负责读取数据并收敛返回字段。 */
    public List<CategoryListItemVO> listEnabled() {
        try {
            // 没有记录时自然返回空列表，JSON 序列化为 []，不返回 null 或数据库实体。
            return mapper.selectEnabled().stream()
                    .map(category -> new CategoryListItemVO(category.getId().toString(), category.getName(), category.getSort()))
                    .toList();
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            // 暂时性故障延续现有 503 语义；SQL 错误继续作为程序异常处理，不用空列表掩盖故障。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    @org.spring.shopcommon.logging.BizLog(action="CATEGORY_CREATE",bizId="#result.categoryId")
    public CategoryVO create(CategoryCreateRequest request) {
        var entity = new CategoryEntity();
        entity.setName(request.getName());
        entity.setSort(request.getSort());
        entity.setEnabled(request.getEnabled());
        try {
            if (mapper.insert(entity) != 1 || entity.getId() == null) {
                throw new IllegalStateException("Category insert did not return a generated ID");
            }
        } catch (DuplicateKeyException duplicate) {
            // 此 INSERT 不提交主键，唯一业务键只有 name；并发或普通重名均固定返回 409。
            throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS);
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            // 不重试结果可能已提交的写操作；SQL 拼写等程序错误仍交全局处理器返回 500。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
        return new CategoryVO(entity.getId().toString(), entity.getName(), entity.getSort(), entity.getEnabled());
    }
}
