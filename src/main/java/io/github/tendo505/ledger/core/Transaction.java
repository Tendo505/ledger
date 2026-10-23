package io.github.tendo505.ledger.core;

import java.time.Instant;
import java.util.List;

/** A posted transaction: its entries always sum to zero (double entry). */
public record Transaction(String id, String kind, String description, Instant createdAt, List<Entry> entries) {

    /** One side of a transaction: money into (positive) or out of (negative) an account, and the balance it left. */
    public record Entry(long id, String accountId, long amount, long balanceAfter, Instant createdAt, String transactionId) {}
}
