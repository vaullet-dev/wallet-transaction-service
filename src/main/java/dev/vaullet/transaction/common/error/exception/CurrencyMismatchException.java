package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.transaction.common.error.TransactionErrorType;
import java.io.Serial;

/** The request's currency is not the deployment's. Nothing is recorded: it was never a transaction. */
public class CurrencyMismatchException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(String requested, String deployment) {
        super(TransactionErrorType.CURRENCY_MISMATCH,
                "This deployment moves %s only; the request is in %s".formatted(deployment, requested));
    }
}
