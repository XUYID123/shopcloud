package org.spring.shoporder.admission;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDependencyGuardTests {
    @Test
    void opensForRealFailuresAndRecoversAfterSuccessfulProbe() throws InterruptedException {
        var guard = new OrderDependencyGuard(true);
        guard.initialize();
        try {
            for (int i = 0; i < 20; i++) {
                reject(guard, OrderDependencyGuard.ADDRESS, ErrorCode.NOT_FOUND);
                reject(guard, OrderDependencyGuard.PRODUCT, ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            assertThat(guard.call(OrderDependencyGuard.ADDRESS, () -> "address")).isEqualTo("address");
            assertThatThrownBy(() -> guard.call(OrderDependencyGuard.PRODUCT, () -> "should not call"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getBusinessCode()).isEqualTo("CIRCUIT_OPEN"));
            Thread.sleep(10_100);
            assertThat(guard.call(OrderDependencyGuard.PRODUCT, () -> "probe")).isEqualTo("probe");
            assertThat(guard.call(OrderDependencyGuard.PRODUCT, () -> "product")).isEqualTo("product");
        } finally {
            DegradeRuleManager.loadRules(List.of());
        }
    }

    private static void reject(OrderDependencyGuard guard, String resource, ErrorCode code) {
        assertThatThrownBy(() -> guard.call(resource, () -> { throw new BusinessException(code); }))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getBusinessCode()).isEqualTo(code.name()));
    }
}
