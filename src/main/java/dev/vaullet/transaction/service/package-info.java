/**
 * The transaction rules: who may ask for what, when a repeated request is a replay and when it is a
 * conflict, what the ledger's answers mean, and where each database transaction begins and ends.
 *
 * <p>Nothing here knows about HTTP. {@code LayeringTest} enforces it, for the reason it does in the
 * ledger: capture, void and expiry will be driven by listeners and sweepers, which have no request
 * to bind. The ledger is called through {@code LedgerReservations} in {@code dao}, which is where
 * its wire format and status codes stay.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.transaction.service;

import org.jspecify.annotations.NullMarked;
