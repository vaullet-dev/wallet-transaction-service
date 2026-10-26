package dev.vaullet.transaction.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A transaction as it stands.
 *
 * @param reservationId the ledger's hold, for a debit it said yes to; null for a credit, and for a
 *     debit that is PENDING or DECLINED
 * @param expiresAt when that hold ends unless captured, as the ledger set it
 * @param allocations the buckets the hold draws on, in the ledger's order; empty without a hold
 * @param declineCode the ledger's reason, for a DECLINED debit: {@code INSUFFICIENT_FUNDS},
 *     {@code RESOURCE_NOT_FOUND} or {@code HOLD_TTL_TOO_LONG}
 */
public record Transaction(
        UUID transactionId,
        UUID accountId,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        @Nullable String reference,
        Map<String, String> metadata,
        @Nullable UUID reservationId,
        @Nullable Instant expiresAt,
        List<Allocation> allocations,
        @Nullable String declineCode,
        Instant createdAt,
        Instant updatedAt) {}
