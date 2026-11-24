package org.spring.shoporder.checkout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
class CheckoutQuoteStore {
    private final JdbcTemplate jdbc;

    CheckoutQuoteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(long userId, long addressId, CheckoutQuote quote, String addressSnapshot, String itemSnapshot) {
        jdbc.update("""
                INSERT INTO checkout_quote
                   (quote_id,user_id,address_id,address_snapshot,item_snapshot,currency,goods_amount_fen,
                    freight_amount_fen,payable_amount_fen,total_weight_gram,price_hash,freight_rule_version,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, quote.quoteId(), userId, addressId, addressSnapshot, itemSnapshot, quote.currency(),
                quote.goodsAmountFen(), quote.freightAmountFen(), quote.payableAmountFen(), quote.totalWeightGram(),
                quote.quoteVersion(), quote.freightRuleVersion(), Timestamp.from(quote.expiresAt().toInstant()));
    }
}
