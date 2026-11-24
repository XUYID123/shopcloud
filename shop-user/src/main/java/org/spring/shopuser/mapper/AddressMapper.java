package org.spring.shopuser.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.spring.shopuser.entity.AddressEntity;
import org.spring.shopuser.vo.AddressVO;

import java.util.List;

@Mapper
public interface AddressMapper {
    String lockOwnerStatus(@Param("userId") long userId);

    String findOwnerStatus(@Param("userId") long userId);

    int countByOwner(@Param("userId") long userId);

    List<AddressVO> listByOwner(@Param("userId") long userId, @Param("limit") int limit);

    AddressVO findByEnabledOwner(@Param("userId") long userId, @Param("addressId") long addressId);

    Long lockOwnedAddress(@Param("userId") long userId, @Param("addressId") long addressId);

    int clearDefault(@Param("userId") long userId, @Param("keepId") Long keepId);

    int insert(AddressEntity address);

    int replace(@Param("addressId") long addressId, @Param("address") AddressEntity address);

    int deleteByOwner(@Param("userId") long userId, @Param("addressId") long addressId);
}
