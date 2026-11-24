package org.spring.shoporder.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.spring.shopcommon.checkout.ProductQuote;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopcommon.result.Result;
import org.spring.shoporder.admission.OrderDependencyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = CheckoutModuleConfiguration.ENABLED, havingValue = "true")
public class CheckoutQuoteService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final String FREIGHT_RULE = "FREIGHT_V1";
    private static final long MAX_WEIGHT = 500_000;
    private static final long MAX_AMOUNT = 1_000_000_000;
    private final CheckoutAddressClient addresses;
    private final CheckoutProductClient products;
    private final CheckoutQuoteStore store;
    private final Set<String> regions;
    private final ObjectMapper json;
    private final OrderDependencyGuard dependencies;

    CheckoutQuoteService(CheckoutAddressClient addresses, CheckoutProductClient products, CheckoutQuoteStore store,
                         ObjectMapper json, @Value("${shop.order.checkout.region-codes}") Set<String> regions,
                         OrderDependencyGuard dependencies) {
        if (regions.isEmpty() || regions.stream().anyMatch(code -> !code.matches("31[0-9]{4}"))) {
            throw new IllegalArgumentException("Checkout regions must be configured as Shanghai district codes");
        }
        this.addresses = addresses;
        this.products = products;
        this.store = store;
        this.json = json;
        this.regions = Set.copyOf(regions);
        this.dependencies = dependencies;
    }

    @BizLog(action = "CHECKOUT_QUOTE_CREATE", bizId = "#result.quoteId")
    public CheckoutQuote create(long userId, long addressId, List<PurchaseItems.Item> requested) {
        var basis = readBasis(userId, addressId, requested);
        List<QuotedItem> items = basis.items();
        var lines = new ArrayList<CheckoutQuote.Line>(items.size());
        long weight = 0;
        long goods = 0;
        for (var item : items) {
            var product = item.product();
            long amount = product.unitPriceFen() * item.quantity();
            weight += product.weightGram() * item.quantity();
            goods += amount;
            lines.add(new CheckoutQuote.Line(product.skuId(), product.productName(), product.specName(),
                    product.unitPriceFen(), item.quantity(), product.weightGram(), amount));
        }
        if (weight > MAX_WEIGHT) throw new BusinessException(ErrorCode.WEIGHT_LIMIT_EXCEEDED);
        long freight = 1000 + ((Math.max(0, weight - 20_000) + 9_999) / 10_000) * 500;
        long payable = goods + freight;
        if (payable > MAX_AMOUNT) throw new BusinessException(ErrorCode.ORDER_AMOUNT_LIMIT_EXCEEDED);

        String version = InternalJwtProtocol.digest(snapshot(basis));
        var expiresAt = OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(VALIDITY), BUSINESS_ZONE);
        var quote = new CheckoutQuote(UUID.randomUUID().toString(), version, "CNY", List.copyOf(lines),
                goods, freight, payable, weight, FREIGHT_RULE, expiresAt);
        try {
            store.insert(userId, addressId, quote, snapshot(basis.address()), snapshot(items));
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
        return quote;
    }

    public String currentVersion(long userId, long addressId, List<PurchaseItems.Item> items) {
        return InternalJwtProtocol.digest(snapshot(readBasis(userId, addressId, items)));
    }

    private QuoteBasis readBasis(long userId, long addressId, List<PurchaseItems.Item> requested) {
        SortedMap<Long, Integer> quantities = PurchaseItems.validated(requested);
        AddressSnapshot address = dependencies.call(OrderDependencyGuard.ADDRESS, () -> lookupAddress(userId, addressId));
        if (!regions.contains(address.regionCode())) throw new BusinessException(ErrorCode.DELIVERY_UNSUPPORTED);
        var lines = dependencies.call(OrderDependencyGuard.PRODUCT, () -> lookupProducts(quantities));
        return new QuoteBasis(userId, addressId, address, lines, FREIGHT_RULE);
    }

    private AddressSnapshot lookupAddress(long userId, long addressId) {
        var address = dependency(() -> addresses.find(addressId, userId));
        if (!Long.toString(addressId).equals(address.addressId()) || !text(address.receiver(), 50)
                || !text(address.detail(), 200) || address.regionCode() == null || !address.regionCode().matches("[0-9]{6}")
                || address.phone() == null || !address.phone().matches("1[3-9][0-9]{9}")) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return new AddressSnapshot(address.receiver(), address.phone(), address.regionCode(), address.detail());
    }

    private List<QuotedItem> lookupProducts(SortedMap<Long, Integer> quantities) {
        List<PurchaseItems.Item> request = quantities.entrySet().stream()
                .map(line -> new PurchaseItems.Item(line.getKey().toString(), line.getValue())).toList();
        var response = dependency(() -> products.quote(new PurchaseItems(request)));
        if (response.lines() == null || response.lines().size() != quantities.size()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        var bySku = new HashMap<String, ProductQuote.Line>();
        for (var line : response.lines()) {
            if (!validProduct(line) || bySku.putIfAbsent(line.skuId(), line) != null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
        }
        var result = new ArrayList<QuotedItem>(request.size());
        for (var item : request) {
            var product = bySku.get(item.skuId());
            if (product == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            result.add(new QuotedItem(product, item.quantity()));
        }
        return List.copyOf(result);
    }

    private static boolean validProduct(ProductQuote.Line line) {
        return line != null && line.skuId() != null && text(line.productId(), 19)
                && text(line.productName(), 100) && text(line.specName(), 100)
                && line.weightGram() != null && line.weightGram() >= 100 && line.weightGram() <= MAX_WEIGHT
                && line.unitPriceFen() != null && line.unitPriceFen() >= 0 && line.unitPriceFen() <= MAX_AMOUNT
                && line.productVersion() != null && line.productVersion() > 0
                && line.skuVersion() != null && line.skuVersion() > 0;
    }

    private static boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static <T> T dependency(Supplier<Result<T>> call) {
        try {
            Result<T> response = call.get();
            if (response == null || !"OK".equals(response.getCode()) || response.getData() == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return response.getData();
        } catch (FeignException unavailable) {
            // Feign failures may carry the response body or URL with private address data.
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String snapshot(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private record AddressSnapshot(String receiver, String phone, String regionCode, String detail) { }
    private record QuotedItem(ProductQuote.Line product, int quantity) { }
    private record QuoteBasis(long userId, long addressId, AddressSnapshot address, List<QuotedItem> items, String freightRule) { }
}
