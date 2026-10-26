package dev.vaullet.transaction.service;

/**
 * Which way the money moves, from the account's side.
 *
 * <p>Generic on purpose, for now: ADR-012 §2's {@code WAGER} and its relatives are what an operator
 * calls a debit, and they will arrive as their own types when something has to treat them
 * differently. Until then two types are the whole of what the ledger can do with an account.
 */
public enum TransactionType {
    /** Money out of the account: held by the ledger now, spent at capture. */
    DEBIT,
    /** Money into the account: recorded now, posted to the ledger at capture. Nothing to hold. */
    CREDIT
}
