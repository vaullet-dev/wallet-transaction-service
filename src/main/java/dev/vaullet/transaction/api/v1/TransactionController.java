package dev.vaullet.transaction.api.v1;

import dev.vaullet.transaction.api.v1.dto.CreateTransactionRequest;
import dev.vaullet.transaction.api.v1.dto.TransactionResponse;
import dev.vaullet.transaction.common.error.exception.InvalidTransactionException;
import dev.vaullet.transaction.service.Transaction;
import dev.vaullet.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /v1/transactions} (ADR-012 §2 and §3): recording a transaction, and reading one back.
 *
 * <p>Translation only, as in the ledger's controllers: bind and validate, pick the service method for
 * the caller, shape the response. The rules, the authorisation and the transaction boundaries are
 * {@link TransactionService}'s; errors propagate to the one exception handler.
 *
 * <p>Two kinds of caller share these endpoints, and the token tells them apart ({@link Callers}): an
 * end user, whose token names their account, works on that account alone; an operator's backend,
 * whose token names none, names the account in the request.
 */
@Validated
@RestController
@Profile("!migrate")
@RequestMapping("/v1/transactions")
@Tag(name = "Transactions", description = "Requests to move money, and their outcomes")
class TransactionController {

    private final TransactionService transactions;

    TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @PostMapping
    @Operation(
            summary = "Record a transaction",
            description = "A DEBIT asks the ledger to hold the amount and answers AUTHORIZED with the hold: its "
                    + "reservation_id, when it expires, and which buckets fund it. A CREDIT is recorded "
                    + "AUTHORIZED; it is posted at capture. Capture and void come later; until then a hold ends "
                    + "when it expires. An operator's backend (transaction:write) names the account and may "
                    + "debit or credit it. An end user (transaction:self) debits their own account, the one "
                    + "their token names. Repeating a request with the same Idempotency-Key answers with the "
                    + "transaction it recorded, as it stands; a different request with the same key is refused.")
    @ApiResponse(responseCode = "201", description = "Recorded, or the transaction this key recorded")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED — a field missing or out of range, an amount "
            + "finer than the currency's minor unit, an operator's request without account_id, or an "
            + "Idempotency-Key that is blank or over 255 characters")
    @ApiResponse(responseCode = "403", description = "ACCESS_DENIED — no transaction:write; or, for an end user, "
            + "a CREDIT, or an account that is not theirs")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND — the ledger knows no such account. The "
            + "transaction is recorded DECLINED, and transaction_id names it")
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_FUNDS — recorded DECLINED, transaction_id names "
            + "it | IDEMPOTENCY_KEY_REUSED — the key recorded a different request | REQUEST_IN_PROGRESS — "
            + "the same request is being answered; repeat it in a moment")
    @ApiResponse(responseCode = "422", description = "CURRENCY_MISMATCH — not the deployment's currency | "
            + "HOLD_TTL_TOO_LONG — recorded DECLINED, transaction_id names it")
    @ApiResponse(responseCode = "503", description = "UPSTREAM_UNAVAILABLE — the ledger did not answer. The debit "
            + "stays PENDING; repeat the request with the same Idempotency-Key")
    ResponseEntity<TransactionResponse> create(
            @Parameter(description = "Your key for this request; a repeat with the same key and request answers "
                            + "with the same transaction", required = true)
                    @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Size(max = 255)
                    String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request,
            @Nullable Principal principal,
            UriComponentsBuilder uriBuilder) {
        Callers.Caller caller = Callers.of(principal);
        Transaction transaction;
        if (caller.account() == null) {
            UUID account = request.accountId();
            if (account == null) {
                // The operator's case; an end user lands here only with a token from before their
                // account existed, and the message says what to do then too.
                throw new InvalidTransactionException("account_id: the request must name the account. An end "
                        + "user's token names theirs once they have one: create it (PUT /api/auth/v1/accounts/me) "
                        + "and sign in again");
            }
            transaction = transactions.create(request.toNewTransaction(account), caller.requester(), idempotencyKey);
        } else {
            // An end user's own account, unless the request names another: then that one, for the rule
            // to refuse, rather than a quiet substitution the caller did not ask for.
            UUID account = request.accountId() != null ? request.accountId() : caller.account();
            transaction = transactions.createOwn(request.toNewTransaction(account), caller.requester(), idempotencyKey);
        }
        URI location = uriBuilder
                .path("/v1/transactions/{id}")
                .buildAndExpand(transaction.transactionId())
                .toUri();
        // 201 on a replay too, as the ledger answers: a caller's retry path should not behave
        // differently from its first attempt, which is the point of the key.
        return ResponseEntity.created(location).body(TransactionResponse.from(transaction));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Fetch a transaction",
            description = "Its status, amount, hold and allocations. An operator's backend (transaction:read) reads "
                    + "any transaction; an end user (transaction:self) reads those on their own account.")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND — no such transaction, or, for an end "
            + "user, not one on their account")
    TransactionResponse get(@PathVariable UUID id, @Nullable Principal principal) {
        Callers.Caller caller = Callers.of(principal);
        UUID account = caller.account();
        return TransactionResponse.from(account == null ? transactions.find(id) : transactions.findOwn(id, account));
    }
}
