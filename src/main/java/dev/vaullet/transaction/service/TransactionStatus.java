package dev.vaullet.transaction.service;

/**
 * Where a transaction is in ADR-012's lifecycle. Stored as the constant's name; the database's
 * {@code transactions_status} check lists the same six.
 *
 * <p>#1 reaches {@link #PENDING}, {@link #AUTHORIZED} and {@link #DECLINED}. The other three exist
 * because the ledger can report them: a request repeated after its hold was released by hand,
 * expired or settled gets the hold as it is now, and the transaction takes its state.
 */
public enum TransactionStatus {
    /** A debit the ledger has not answered yet: the call failed or is in flight. */
    PENDING,
    /** The ledger holds a debit's money; a credit is recorded. Either waits for capture. */
    AUTHORIZED,
    /** The ledger refused the debit. Terminal. */
    DECLINED,
    /** The money moved: the hold settled. Terminal. */
    CAPTURED,
    /** The hold was released and nothing moved. Terminal. */
    VOIDED,
    /** The hold reached its expiry uncaptured and was released. Terminal. */
    EXPIRED
}
