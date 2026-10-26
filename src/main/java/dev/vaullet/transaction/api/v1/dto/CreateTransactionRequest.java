package dev.vaullet.transaction.api.v1.dto;

import dev.vaullet.transaction.service.NewTransaction;
import dev.vaullet.transaction.service.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /v1/transactions} (ADR-012 §2).
 *
 * <p>A dedicated input type, so there is no {@code transaction_id}, {@code status} or
 * {@code allocations} for a caller to set. The constraints mirror the columns, so a value the
 * database would refuse is a 400 naming the field rather than a 500.
 *
 * <p><b>{@code type} is a string with a pattern, not the enum</b>, for the ledger's reason: an enum
 * refuses an unknown value inside Jackson, before validation runs, and that 400 has no {@code code}.
 *
 * @param accountId the account the money moves on. An operator's backend names it. An end user may
 *     leave it out: their token names their account, and naming any other is refused.
 * @param amount positive, at most four decimal places here and at most the currency's minor unit in
 *     the service: {@code 20.001} is not an amount of EUR
 * @param currency the deployment's currency; any other is {@code CURRENCY_MISMATCH}
 * @param expiresInSeconds how long a debit's hold may stay uncaptured. Omitted means the ledger's
 *     default, 300s; its ceiling is the ledger's {@code max_hold_seconds}. A credit holds nothing and
 *     takes none.
 * @param capture ADR-012's authorize-and-settle in one call, whose default there is {@code true}.
 *     Refused when true, and not read otherwise: #1 only authorizes, and an integrator who asks for a
 *     capture must hear that none happened rather than find a hold. Jackson ignores a field it does
 *     not know, so without this component {@code "capture": true} would pass in silence.
 * @param reference the caller's own reference, such as a bet id. Stored and returned, never
 *     interpreted, not unique.
 * @param metadata the caller's own key-value pairs: at most 20, keys up to 40 characters, values up
 *     to 500, strings both
 */
public record CreateTransactionRequest(
        @Schema(example = "3f1a8c7e-2b4d-4e6f-9a01-5c7d8e9f0a1b") @Nullable UUID accountId,
        @NotNull
                @Pattern(regexp = "DEBIT|CREDIT", message = "must be DEBIT or CREDIT")
                @Schema(allowableValues = {"DEBIT", "CREDIT"}, example = "DEBIT")
                String type,
        @NotNull
                @DecimalMin(value = "0.0001", message = "must be greater than zero")
                @Digits(integer = 16, fraction = 4)
                @Schema(type = "string", example = "20.00", description = "Decimal string, never a JSON number")
                BigDecimal amount,
        @NotNull
                @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code, such as EUR")
                @Schema(example = "EUR")
                String currency,
        @Positive @Schema(example = "300") @Nullable Integer expiresInSeconds,
        @AssertFalse(message = "must be false or left out: a transaction is only authorized until capture exists")
                @Schema(example = "false", description = "ADR-012's capture. Only false for now")
                @Nullable Boolean capture,
        @Size(min = 1, max = 255) @Schema(example = "bet-8891") @Nullable String reference,
        @Size(max = 20)
                @Schema(example = "{\"market\": \"match-winner\"}")
                @Nullable Map<@NotBlank @Size(max = 40) String, @NotNull @Size(max = 500) String> metadata) {

    /** The request as the service takes it, for this account. Only after validation. */
    public NewTransaction toNewTransaction(UUID account) {
        return new NewTransaction(
                account,
                TransactionType.valueOf(type),
                amount,
                currency,
                expiresInSeconds,
                reference,
                metadata == null ? Map.of() : metadata);
    }
}
