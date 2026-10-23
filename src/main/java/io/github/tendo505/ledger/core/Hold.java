package io.github.tendo505.ledger.core;

import java.time.Instant;

/**
 * An authorization, like a card payment before it settles: the amount is
 * reserved on {@code accountId} until it is captured (paid to
 * {@code destinationId}, possibly less than reserved), voided, or expires.
 */
public record Hold(
        String id,
        String accountId,
        String destinationId,
        long amount,
        Status status,
        Long capturedAmount,
        String transactionId,
        Instant expiresAt,
        Instant createdAt) {

    public enum Status { PENDING, CAPTURED, VOIDED, EXPIRED }
}
