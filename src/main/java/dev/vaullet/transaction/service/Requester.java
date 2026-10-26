package dev.vaullet.transaction.service;

import org.jspecify.annotations.Nullable;

/**
 * Who asked for a transaction, as their token says.
 *
 * @param subject the token's {@code sub}: a person's Keycloak id, or an operator client's service
 *     account. It is also the scope of the caller's {@code Idempotency-Key}s, so two callers' keys
 *     never meet.
 * @param clientId the client the token was issued to ({@code azp}), for the record. Null where the
 *     token names none.
 */
public record Requester(String subject, @Nullable String clientId) {}
