package org.spring.shopuser.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopuser.config.AddressProperties;
import org.spring.shopuser.dto.AddressInput;
import org.spring.shopuser.entity.AddressEntity;
import org.spring.shopuser.mapper.AddressMapper;
import org.spring.shopuser.vo.AddressVO;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

@Service
public class AddressService {
    private static final int ADDRESS_LIMIT = 20;

    private final AddressMapper addresses;
    private final AddressProperties properties;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public AddressService(AddressMapper addresses, AddressProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.addresses = addresses;
        this.properties = properties;
        this.writes = new TransactionTemplate(transactionManager);
        this.writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.reads.setReadOnly(true);
    }

    @BizLog(action = "ADDRESS_CREATE", bizId = "#result.addressId")
    public AddressVO create(long userId, AddressInput input) {
        validateRegion(input);
        return writeForOwner(userId, () -> insertForOwner(userId, input));
    }

    @BizLog(action = "ADDRESS_LIST")
    public List<AddressVO> list(long userId) {
        return inTransaction(reads, () -> {
            requireEnabled(addresses.findOwnerStatus(userId));
            return addresses.listByOwner(userId, ADDRESS_LIMIT);
        });
    }

    @BizLog(action = "ADDRESS_ORDER_LOOKUP", bizId = "#p1")
    public AddressVO findForOrder(long userId, long addressId) {
        return inTransaction(reads, () -> {
            AddressVO address = addresses.findByEnabledOwner(userId, addressId);
            if (address == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            return address;
        });
    }

    @BizLog(action = "ADDRESS_UPDATE", bizId = "#addressId")
    public AddressVO replace(long userId, long addressId, AddressInput input) {
        return writeForOwner(userId, () -> {
            if (addresses.lockOwnedAddress(userId, addressId) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            validateRegion(input);
            if (input.isDefault()) addresses.clearDefault(userId, addressId);
            var address = toEntity(userId, input);
            // A full replacement with the same values may report no changed rows.
            addresses.replace(addressId, address);
            return toView(addressId, address);
        });
    }

    @BizLog(action = "ADDRESS_DELETE", bizId = "#addressId")
    public void delete(long userId, long addressId) {
        writeForOwner(userId, () -> {
            if (addresses.deleteByOwner(userId, addressId) == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            return null;
        });
    }

    private AddressVO insertForOwner(long userId, AddressInput input) {
        if (addresses.countByOwner(userId) >= ADDRESS_LIMIT) {
            throw new BusinessException(ErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }
        if (input.isDefault()) addresses.clearDefault(userId, null);
        var address = toEntity(userId, input);
        if (addresses.insert(address) != 1 || address.getId() == null) {
            throw new IllegalStateException("Address insert did not return a generated ID");
        }
        return toView(address.getId(), address);
    }

    private <T> T writeForOwner(long userId, Supplier<T> operation) {
        return inTransaction(writes, () -> {
            // Serialize address mutations for the same owner before acquiring address locks.
            requireEnabled(addresses.lockOwnerStatus(userId));
            return operation.get();
        });
    }

    private static <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
        try {
            return transaction.execute(status -> operation.get());
        } catch (CannotCreateTransactionException | TransientDataAccessException |
                 DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private static void requireEnabled(String status) {
        if (!"ENABLED".equals(status)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void validateRegion(AddressInput input) {
        if (!properties.regions().containsKey(input.regionCode())) {
            throw new BusinessException(ErrorCode.ADDRESS_REGION_UNSUPPORTED);
        }
    }

    private static AddressEntity toEntity(long userId, AddressInput input) {
        return new AddressEntity(userId, input.receiver(), input.phone(), input.regionCode(), input.detail(),
                input.isDefault());
    }

    private static AddressVO toView(long addressId, AddressEntity address) {
        return new AddressVO(Long.toString(addressId), address.getReceiver(), address.getPhone(),
                address.getRegionCode(), address.getDetail(), address.isDefaultAddress());
    }
}
