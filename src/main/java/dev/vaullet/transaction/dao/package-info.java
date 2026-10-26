/**
 * Everything that leaves the process: the SQL against {@code transactions_db}, and the HTTP calls
 * to the ledger and to Keycloak's token endpoint. Nothing outside a {@code dao} package touches
 * {@code JdbcTemplate}, a rule {@code LayeringTest} enforces, and nothing HTTP-shaped leaves this
 * package: callers get rows and outcomes, not status codes.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.transaction.dao;

import org.jspecify.annotations.NullMarked;
