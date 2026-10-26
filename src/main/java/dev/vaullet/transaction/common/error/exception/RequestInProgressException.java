package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.transaction.common.error.TransactionErrorType;
import java.io.Serial;
import java.util.UUID;

/** The same request's call to the ledger is in flight; a retry with the same key gets its outcome. */
public class RequestInProgressException extends TransactionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RequestInProgressException(UUID transactionId) {
        super(TransactionErrorType.REQUEST_IN_PROGRESS, transactionId,
                "This request is still being answered. Repeat it with the same Idempotency-Key in a moment.");
    }
}
