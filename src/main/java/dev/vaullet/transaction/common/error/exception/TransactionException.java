package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.ErrorType;
import java.io.Serial;
import java.util.UUID;

/**
 * A failure about one transaction that exists, recorded by this caller: its problem+json body names
 * it in {@code transaction_id}, so the caller can look it up without having to have kept the
 * {@code Idempotency-Key} it used. {@code TransactionApiExceptionHandler} adds the field.
 *
 * <p>Only for transactions the caller asked for themselves. A body never names a transaction the
 * caller could not read.
 */
public abstract class TransactionException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID transactionId;

    protected TransactionException(ErrorType errorType, UUID transactionId, String message) {
        super(errorType, message);
        this.transactionId = transactionId;
    }

    protected TransactionException(ErrorType errorType, UUID transactionId, String message, Throwable cause) {
        super(errorType, message, cause);
        this.transactionId = transactionId;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
