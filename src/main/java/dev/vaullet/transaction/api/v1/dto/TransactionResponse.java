package dev.vaullet.transaction.api.v1.dto;

import dev.vaullet.transaction.service.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A transaction, as the API represents it: the answer to {@code POST /v1/transactions}, to its
 * replays, and to {@code GET /v1/transactions/{id}}. One shape for all three, as the ledger has one
 * for a hold: a caller polling the GET wants exactly what the POST returned.
 *
 * <p>{@code expires_at} is an absolute instant rather than the {@code expires_in_seconds} the request
 * took: only the ledger knows when the hold was placed, and so when it ends.
 *
 * @param status {@code AUTHORIZED} for a debit the ledger holds and for a recorded credit;
 *     {@code PENDING} while the ledger has not answered; {@code DECLINED}, {@code CAPTURED},
 *     {@code VOIDED} or {@code EXPIRED} at the end
 * @param reservationId the ledger's hold, for a debit it said yes to
 * @param declineCode why the ledger declined, on a {@code DECLINED} debit
 */
@Schema(name = "Transaction", description = "A request to move money, and what became of it")
public record TransactionResponse(
        UUID transactionId,
        UUID accountId,
        @Schema(allowableValues = {"DEBIT", "CREDIT"}, example = "DEBIT") String type,
        @Schema(allowableValues = {"PENDING", "AUTHORIZED", "DECLINED", "CAPTURED", "VOIDED", "EXPIRED"},
                        example = "AUTHORIZED")
                String status,
        @Schema(type = "string", example = "20.00", description = "Decimal string, never a JSON number") String amount,
        @Schema(example = "EUR") String currency,
        @Schema(example = "bet-8891") @Nullable String reference,
        Map<String, String> metadata,
        @Nullable UUID reservationId,
        @Nullable Instant expiresAt,
        List<AllocationResponse> allocations,
        @Schema(example = "INSUFFICIENT_FUNDS") @Nullable String declineCode,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.transactionId(),
                transaction.accountId(),
                transaction.type().name(),
                transaction.status().name(),
                transaction.amount().toPlainString(),
                transaction.currency(),
                transaction.reference(),
                transaction.metadata(),
                transaction.reservationId(),
                transaction.expiresAt(),
                transaction.allocations().stream().map(AllocationResponse::from).toList(),
                transaction.declineCode(),
                transaction.createdAt(),
                transaction.updatedAt());
    }
}
