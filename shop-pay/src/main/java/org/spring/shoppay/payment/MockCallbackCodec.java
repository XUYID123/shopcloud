package org.spring.shoppay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
class MockCallbackCodec {
    static final int MAX_BYTES = 16 * 1024;
    private final ObjectMapper json;
    private final byte[] secret;
    private final String merchant;

    MockCallbackCodec(ObjectMapper json, @Value("${shop.pay.mock.secret}") String secret,
                      @Value("${shop.pay.mock.merchant}") String merchant) {
        if (secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || !TradeEvents.validOrderNo(merchant)) {
            throw new IllegalArgumentException("Configure a valid Mock merchant and a distinct secret of at least 32 bytes");
        }
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.merchant = merchant;
    }

    Verified verify(byte[] body, String timestamp, String nonce, String signature) {
        if (body.length == 0 || body.length > MAX_BYTES || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || nonce == null || !nonce.matches("[A-Za-z0-9_-]{1,128}")
                || signature == null || !signature.matches("[A-Za-z0-9+/]{43}=")) throw bad();
        long now = Instant.now().getEpochSecond();
        long sent = Long.parseLong(timestamp);
        if (sent < now - 300 || sent > now + 300 || !MessageDigest.isEqual(
                Base64.getDecoder().decode(signature), mac(body, timestamp, nonce))) throw bad();
        return new Verified(parse(body), nonce, hash(body));
    }

    Proof sign(byte[] body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        return new Proof(timestamp, nonce, Base64.getEncoder().encodeToString(mac(body, timestamp, nonce)));
    }

    byte[] encode(String eventId, String paymentNo, long amount, Instant paidAt) {
        try {
            return json.writeValueAsBytes(new Payload(eventId, paymentNo, "mock-" + eventId, merchant,
                    amount, "CNY", "SUCCEEDED", paidAt.toString()));
        } catch (IOException failure) {
            throw new IllegalStateException("Mock event encoding failed");
        }
    }

    Payload parse(byte[] body) {
        try {
            Payload value = json.readValue(body, Payload.class);
            if (value == null || !TradeEvents.validOrderNo(value.eventId()) || !TradeEvents.validOrderNo(value.paymentNo())
                    || !TradeEvents.validOrderNo(value.providerTransactionNo()) || !merchant.equals(value.merchantId())
                    || value.amountFen() == null || value.amountFen() < 0 || value.amountFen() > 1_000_000_000
                    || !"CNY".equals(value.currency()) || !"SUCCEEDED".equals(value.status())
                    || value.paidAt() == null || value.paidInstant().isAfter(Instant.now().plusSeconds(300))) throw bad();
            return value;
        } catch (IOException | java.time.DateTimeException invalid) {
            throw bad();
        }
    }

    private byte[] mac(byte[] body, String timestamp, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            mac.update((byte) '\n');
            return mac.doFinal();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Unable to calculate Mock signature", impossible);
        }
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static BusinessException bad() { return new BusinessException(ErrorCode.BAD_CALLBACK); }

    record Payload(@JsonDeserialize(using = PurchaseItems.StringId.class) String eventId,
                   @JsonDeserialize(using = PurchaseItems.StringId.class) String paymentNo,
                   @JsonDeserialize(using = PurchaseItems.StringId.class) String providerTransactionNo,
                   String merchantId, Long amountFen, String currency, String status, String paidAt) {
        Instant paidInstant() { return OffsetDateTime.parse(paidAt).toInstant(); }
    }
    record Verified(Payload payload, String nonce, String digest) { }
    record Proof(String timestamp, String nonce, String signature) { }
}
