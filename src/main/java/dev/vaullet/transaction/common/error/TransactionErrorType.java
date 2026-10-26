package dev.vaullet.transaction.common.error;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.ErrorType;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The error codes that belong to this service, alongside the platform ones in
 * {@link CommonErrorType}. ADR-012 §7 is the catalogue they come from.
 *
 * <p>Two of them are the ledger's too. {@code INSUFFICIENT_FUNDS} and {@code HOLD_TTL_TOO_LONG} are
 * the ledger's answers, passed through with the same code, status, type and meaning: an integrator
 * sees one catalogue, and does not have to learn that the transaction service and the ledger are two
 * services. The platform's two rules disagree about where they belong now. The ledger's
 * {@code LedgerErrorType} says a code becomes shared the day a second service raises it, and this
 * service is the second; {@code CommonErrorType} admits only what a service that knows nothing about
 * money would raise, which these are not. Until that is settled, each service declares them, and
 * they must stay identical.
 *
 * <p>Adding an entry is additive and safe. Changing what an existing entry <em>means</em> is a
 * breaking change for every caller that branches on it, so it needs a new major API version
 * (ADR-011 §4).
 */
public enum TransactionErrorType implements ErrorType {

    /**
     * The ledger refused the hold: the account's available balance is less than the amount. A 409, as
     * on the ledger: the request is well-formed and would succeed against a different balance.
     */
    INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.CONFLICT, "Insufficient funds"),

    /** The hold asked for outlives the deployment's {@code max_hold_seconds} (ADR-004). */
    HOLD_TTL_TOO_LONG("hold-ttl-too-long", HttpStatus.UNPROCESSABLE_CONTENT, "Hold TTL too long"),

    /** The request's currency is not the deployment's (ADR-004: one currency per deployment). */
    CURRENCY_MISMATCH("currency-mismatch", HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),

    /**
     * This {@code Idempotency-Key} recorded a different request (ADR-012, "Idempotency semantics").
     * Answering it with the first request's outcome would tell the caller that something happened
     * which they did not ask for, which is the dangerous failure.
     */
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", HttpStatus.CONFLICT, "Idempotency key reused"),

    /**
     * The same request is being answered right now: its call to the ledger is in flight. Retry with
     * the same key in a moment and get its outcome (ADR-012, "Idempotency semantics").
     */
    REQUEST_IN_PROGRESS("request-in-progress", HttpStatus.CONFLICT, "Request in progress");

    private final URI type;
    private final HttpStatus status;
    private final String title;

    TransactionErrorType(String slug, HttpStatus status, String title) {
        this.type = ErrorType.documentationUri(slug);
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public URI type() {
        return type;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
