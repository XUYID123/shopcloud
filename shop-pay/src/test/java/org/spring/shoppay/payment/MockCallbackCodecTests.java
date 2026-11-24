package org.spring.shoppay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockCallbackCodecTests {
    private final MockCallbackCodec codec = new MockCallbackCodec(new ObjectMapper(),
            "isolated-mock-signature-fixture-key", "flour-dev");

    @Test
    void verifiesExactBytesAndExtractsBoundPaymentFacts() {
        byte[] body = codec.encode("test-event", "test-payment", 7400, Instant.now());
        var proof = codec.sign(body);
        var verified = codec.verify(body, proof.timestamp(), proof.nonce(), proof.signature());
        assertThat(verified.payload().amountFen()).isEqualTo(7400);
        assertThat(verified.payload().paymentNo()).isEqualTo("test-payment");
        assertThat(verified.digest()).matches("[a-f0-9]{64}");
        byte[] altered = Arrays.copyOf(body, body.length + 1);
        altered[body.length] = ' ';
        assertThatThrownBy(() -> codec.verify(altered, proof.timestamp(), proof.nonce(), proof.signature()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsStaleHeadersWrongMerchantAndOversizedBody() {
        byte[] body = codec.encode("test-event", "test-payment", 7400, Instant.now());
        var proof = codec.sign(body);
        assertThatThrownBy(() -> codec.verify(body, "10", proof.nonce(), proof.signature()))
                .isInstanceOf(BusinessException.class);
        byte[] foreign = new String(body, StandardCharsets.UTF_8).replace("flour-dev", "foreign")
                .getBytes(StandardCharsets.UTF_8);
        var foreignProof = codec.sign(foreign);
        assertThatThrownBy(() -> codec.verify(foreign, foreignProof.timestamp(), foreignProof.nonce(), foreignProof.signature()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.verify(new byte[16385], proof.timestamp(), proof.nonce(), proof.signature()))
                .isInstanceOf(BusinessException.class);
    }
}
