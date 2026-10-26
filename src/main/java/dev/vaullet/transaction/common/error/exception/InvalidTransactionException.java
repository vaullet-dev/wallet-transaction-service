package dev.vaullet.transaction.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.CommonErrorType;
import java.io.Serial;

/**
 * A request that is well-formed JSON with every field in range, and still not a transaction this
 * deployment can record: an amount finer than the currency's minor unit, or a hold asked of a
 * credit. {@code VALIDATION_FAILED}, like any other invalid field, because to the caller it is one;
 * it is checked in the service only because the rule depends on the deployment's currency.
 */
public class InvalidTransactionException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidTransactionException(String detail) {
        super(CommonErrorType.VALIDATION_FAILED, detail);
    }
}
