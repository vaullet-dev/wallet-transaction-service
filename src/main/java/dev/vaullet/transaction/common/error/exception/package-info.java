/**
 * This service's concrete throwables. The catalogue they carry, {@link
 * dev.vaullet.transaction.common.error.TransactionErrorType}, lives one package up, alongside the
 * advice that turns it into a response.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.transaction.common.error.exception;

import org.jspecify.annotations.NullMarked;
