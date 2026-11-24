package org.spring.shopcommon.protection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NacosQpsRuleTests {
    @Test
    void acceptsOnlyOneBoundedIntegerThreshold() {
        assertThat(NacosQpsRule.parse("{\"qps\":200}")).isEqualTo(200);
        for (String invalid : new String[]{null, "", "{\"qps\":0}", "{\"qps\":100001}", "{\"qps\":1.5}",
                "{\"qps\":\"200\"}", "{\"qps\":20,\"qps\":2}", "{\"qps\":20}{}", "{\"qps\":20,\"extra\":1}"}) {
            assertThatThrownBy(() -> NacosQpsRule.parse(invalid)).isInstanceOf(IllegalStateException.class);
        }
    }
}
