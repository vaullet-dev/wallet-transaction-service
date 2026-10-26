/**
 * The error codes that belong to this service, and the one advice that turns every error into a
 * {@code problem+json} body (ADR-011 §7).
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.transaction.common.error;

import org.jspecify.annotations.NullMarked;
