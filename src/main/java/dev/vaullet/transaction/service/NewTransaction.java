package dev.vaullet.transaction.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A transaction as a caller asks for it: everything the request decides, and nothing the service or
 * the ledger does. Its {@link Fingerprint} is what a repeated {@code Idempotency-Key} is compared on.
 *
 * @param accountId the account the money moves on: the operator's choice, or for an end user their
 *     own, from their token
 * @param expiresInSeconds how long a debit's hold may stay uncaptured; null is the ledger's default.
 *     A credit holds nothing and must not ask
 * @param reference the caller's own reference, such as a bet id; not unique, not interpreted
 * @param metadata the caller's own key-value pairs, stored and returned as given
 */
public record NewTransaction(
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        @Nullable Integer expiresInSeconds,
        @Nullable String reference,
        Map<String, String> metadata) {

    public NewTransaction {
        metadata = Map.copyOf(metadata);
    }
}
