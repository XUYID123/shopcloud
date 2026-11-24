package org.spring.shoporder.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.checkout.ProductQuote;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.Result;
import org.spring.shoporder.admission.OrderDependencyGuard;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckoutQuoteServiceTests {
    private final CheckoutAddressClient addresses = mock(CheckoutAddressClient.class);
    private final CheckoutProductClient products = mock(CheckoutProductClient.class);
    private final CheckoutQuoteStore store = mock(CheckoutQuoteStore.class);
    private final CheckoutQuoteService checkout = new CheckoutQuoteService(addresses, products, store,
            new ObjectMapper(), Set.of("310115"), new OrderDependencyGuard(false));

    @BeforeEach
    void ownedAddress() {
        when(addresses.find(34, 12)).thenReturn(Result.success(address("310115")));
    }

    @Test
    void returnsAndPersistsTheDocumentedQuote() {
        when(products.quote(any())).thenReturn(priced(line("56", 5000, 3200, 1)));
        Instant start = Instant.now();
        var result = checkout.create(12, 34, List.of(item("56", 2)));

        assertThat(result.goodsAmountFen()).isEqualTo(6400);
        assertThat(result.freightAmountFen()).isEqualTo(1000);
        assertThat(result.payableAmountFen()).isEqualTo(7400);
        assertThat(result.totalWeightGram()).isEqualTo(10000);
        assertThat(result.items()).containsExactly(new CheckoutQuote.Line("56", "Test flour", "5kg", 3200, 2, 5000, 6400));
        assertThat(result.quoteVersion()).matches("[0-9a-f]{64}");
        assertThat(result.expiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(result.expiresAt().toInstant()).isBetween(start.plusSeconds(299), Instant.now().plusSeconds(301));
        verify(store).insert(eq(12L), eq(34L), eq(result), contains("\"regionCode\":\"310115\""),
                contains("\"skuVersion\":1"));
    }

    @Test
    void appliesExactFreightBoundariesAndThe500KgLimit() {
        when(products.quote(any())).thenReturn(priced(line("56", 5000, 3200, 1)));
        assertThat(checkout.create(12, 34, List.of(item("56", 4))).freightAmountFen()).isEqualTo(1000);
        assertThat(checkout.create(12, 34, List.of(item("56", 6))).freightAmountFen()).isEqualTo(1500);
        assertThat(checkout.create(12, 34, List.of(item("56", 7))).freightAmountFen()).isEqualTo(2000);
        assertThat(checkout.create(12, 34, List.of(item("56", 100))).freightAmountFen()).isEqualTo(25000);
        when(products.quote(any())).thenReturn(priced(line("56", 50001, 3200, 1)));
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 10))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("WEIGHT_LIMIT_EXCEEDED");
    }

    @Test
    void hashesStableSkuOrderAndDetectsAnAuthoritativeVersionChange() {
        var lines = List.of(item("56", 2), item("78", 1));
        when(products.quote(any())).thenReturn(priced(line("78", 1000, 2000, 1), line("56", 5000, 3200, 1)));
        var initial = checkout.create(12, 34, lines);
        var reordered = checkout.create(12, 34, List.of(lines.get(1), lines.get(0)));
        assertThat(reordered.quoteId()).isNotEqualTo(initial.quoteId());
        assertThat(reordered.quoteVersion()).isEqualTo(initial.quoteVersion());
        when(products.quote(any())).thenReturn(priced(line("56", 5000, 3200, 2), line("78", 1000, 2000, 1)));
        assertThat(checkout.create(12, 34, lines).quoteVersion()).isNotEqualTo(initial.quoteVersion());
    }

    @Test
    void rejectsDuplicateSkuAndOutOfRegionWithoutFetchingProducts() {
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 2), item("56", 1))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("VALIDATION_ERROR");
        when(addresses.find(34, 12)).thenReturn(Result.success(address("310101")));
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 2))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("DELIVERY_UNSUPPORTED");
        verifyNoInteractions(products, store);
    }

    @Test
    void dependencyErrorsAndAmountOverflowDoNotPersistAQuote() {
        when(products.quote(any())).thenThrow(new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 2))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("PRODUCT_UNAVAILABLE");
        doReturn(priced(line("56", 100, 10_000_000, 1))).when(products).quote(any());
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 100))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("ORDER_AMOUNT_LIMIT_EXCEEDED");
        when(products.quote(any())).thenReturn(priced(line("78", 5000, 3200, 1)));
        assertThatThrownBy(() -> checkout.create(12, 34, List.of(item("56", 1))))
                .extracting(error -> ((BusinessException) error).getBusinessCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(store);
    }

    private static PurchaseItems.Item item(String skuId, int quantity) {
        return new PurchaseItems.Item(skuId, quantity);
    }

    private static CheckoutAddressClient.Address address(String region) {
        return new CheckoutAddressClient.Address("34", "Test receiver", "13800000000", region, "Test address", true);
    }

    private static ProductQuote.Line line(String sku, long weight, long price, long version) {
        return new ProductQuote.Line(sku, "22", "Test flour", "5kg", weight, price, 1L, version);
    }

    private static Result<ProductQuote> priced(ProductQuote.Line... lines) {
        return Result.success(new ProductQuote(List.of(lines)));
    }
}
