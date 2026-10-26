package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import java.io.Serial;
import java.util.UUID;

/**
 * The ledger could not be asked, or did not answer: the debit is recorded and stays PENDING.
 *
 * <p>{@code UPSTREAM_UNAVAILABLE}, the platform's 503, but a {@link TransactionException}, so the
 * body names the PENDING debit in {@code transaction_id}: the transaction exists, and a caller that
 * has lost its key can still find it. The way on is the same request with the same key, which asks
 * the ledger again.
 */
public class DebitPendingException extends TransactionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DebitPendingException(UUID transactionId, UpstreamUnavailableException cause) {
        super(CommonErrorType.UPSTREAM_UNAVAILABLE, transactionId,
                "The ledger could not be asked to hold the money, so the debit is PENDING. Repeat the request "
                        + "with the same Idempotency-Key.",
                cause);
    }
}
