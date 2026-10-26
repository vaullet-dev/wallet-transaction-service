package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.transaction.common.error.TransactionErrorType;
import java.io.Serial;
import java.util.UUID;

/** This caller's {@code Idempotency-Key} already recorded a different request. */
public class IdempotencyKeyReusedException extends TransactionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(UUID transactionId) {
        super(TransactionErrorType.IDEMPOTENCY_KEY_REUSED, transactionId,
                "This Idempotency-Key recorded a different request. Use a new key for a new transaction.");
    }
}
