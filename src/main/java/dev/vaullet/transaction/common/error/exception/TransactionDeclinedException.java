package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.common.error.ErrorType;
import java.io.Serial;
import java.util.UUID;

/**
 * The ledger refused the hold, and the transaction is recorded as DECLINED.
 *
 * <p>Thrown on the request that was refused and on every replay of it, with the same type and the
 * ledger's own detail each time: a retry gets the answer the first attempt got, and the ledger is
 * not asked twice. {@code INSUFFICIENT_FUNDS} is a business signal, not a fault — ADR-004 expects a
 * permanently non-zero rate of it — so it is logged at debug, like every {@code ApplicationException}.
 */
public class TransactionDeclinedException extends TransactionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TransactionDeclinedException(ErrorType errorType, UUID transactionId, String detail) {
        super(errorType, transactionId, detail);
    }
}
