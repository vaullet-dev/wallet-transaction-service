package dev.vaullet.transaction.common.error;

import dev.vaullet.common.security.SecurityApiExceptionHandler;
import dev.vaullet.transaction.common.error.exception.TransactionException;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * The shared exception handler, with one field added.
 *
 * <p>A failure about a transaction the caller recorded — declined by the ledger, a reused key, a
 * request still in flight — carries {@code transaction_id} next to {@code code}: the declined
 * transaction is a record the caller can read back, and a caller who reused a key needs to know
 * which transaction the key belongs to. Written in snake_case literally, as {@code ProblemDetails}
 * writes {@code trace_id}: extension properties are a map, and the naming strategy does not rewrite
 * map keys.
 *
 * <p>Everything else — the single {@code ApplicationException} handler, the validation bodies, the
 * opaque 500, the {@code AccessDeniedException} mapping — comes from the library. Declaring this bean
 * is what makes both of its auto-configurations back off, so there is exactly one advice producing
 * error bodies, and the more specific handler below wins over the library's by exception depth, not
 * by ordering.
 */
@RestControllerAdvice
class TransactionApiExceptionHandler extends SecurityApiExceptionHandler {

    TransactionApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        super(tracer);
    }

    @ExceptionHandler(TransactionException.class)
    ProblemDetail handleTransactionException(TransactionException ex, WebRequest request) {
        ProblemDetail problem = handleApplicationException(ex, request);
        problem.setProperty("transaction_id", ex.transactionId());
        return problem;
    }
}
