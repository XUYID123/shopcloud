package org.spring.shoporder.admission;

import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import jakarta.annotation.PostConstruct;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class OrderDependencyGuard {
    public static final String ADDRESS = "dependency.user.address";
    public static final String PRODUCT = "dependency.product.quote";
    private final boolean enabled;

    public OrderDependencyGuard(@Value("${shop.order.admission.circuit-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    void initialize() {
        if (enabled) {
            DegradeRuleManager.loadRules(List.of(errors(ADDRESS), slow(ADDRESS), errors(PRODUCT), slow(PRODUCT)));
        }
    }

    public <T> T call(String resource, Supplier<T> dependency) {
        if (!enabled) return dependency.get();
        try (var entry = SphU.entry(resource)) {
            try {
                return dependency.get();
            } catch (BusinessException failure) {
                if (ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(failure.getBusinessCode())) {
                    Tracer.traceEntry(failure, entry);
                }
                throw failure;
            }
        } catch (BlockException open) {
            throw new BusinessException(ErrorCode.CIRCUIT_OPEN);
        }
    }

    private static DegradeRule errors(String resource) {
        return base(resource).setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO).setCount(0.5);
    }

    private static DegradeRule slow(String resource) {
        return base(resource).setGrade(RuleConstant.DEGRADE_GRADE_RT).setCount(500).setSlowRatioThreshold(0.5);
    }

    private static DegradeRule base(String resource) {
        return new DegradeRule(resource).setTimeWindow(10).setStatIntervalMs(10_000).setMinRequestAmount(20);
    }
}
