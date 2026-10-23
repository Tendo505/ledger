package io.github.tendo505.ledger.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Balances are in minor units (cents). {@code pending} is reserved by open holds; only {@code available()} can be spent. */
public record Account(String id, String name, String currency, boolean allowNegative, long balance, long pending, Instant createdAt) {
    @JsonProperty("available")
    public long available() {
        return balance - pending;
    }
}
