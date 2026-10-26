package dev.vaullet.transaction.api.v1;

import dev.vaullet.transaction.service.Requester;
import java.security.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, as far as the controller needs to know: who asked, for the record and the
 * idempotency key's scope, and which account their token names, if any.
 *
 * <p>The account in the token is what tells the two kinds of caller apart here. An end user's token
 * names their account ({@code account_id}, gitops {@code realm/16-vaullet-account-id.yaml}); an
 * operator's backend signs in with client credentials and its token names none. Which of them may
 * do what is still {@code TransactionAccess}'s to say: this only picks the method, and each method's
 * rule checks the caller again.
 */
final class Callers {

    /**
     * Who a request without a token is recorded as. Only the {@code local} profile lets one through:
     * everywhere else every request is authenticated before it reaches a controller.
     */
    static final String LOCAL_SUBJECT = "local";

    /**
     * @param account the account the caller's token names: an end user's own. Null for an operator's
     *     backend, whose token names none.
     */
    record Caller(Requester requester, @Nullable UUID account) {}

    private Callers() {}

    static Caller of(@Nullable Principal principal) {
        if (principal == null) {
            // The local profile's anonymous caller. It holds the operator's scopes there
            // (application.yaml, vaullet.security.local) and names no account.
            return new Caller(new Requester(LOCAL_SUBJECT, null), null);
        }
        if (principal instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            @Nullable String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                // The idempotency key is scoped to the caller, so a caller nobody can name cannot
                // have one. Keycloak sets sub on every token of a client with the basic scope, and on
                // every client-credentials token.
                throw new AccessDeniedException("The token does not say who its holder is: it carries no sub");
            }
            return new Caller(new Requester(subject, jwt.getClaimAsString("azp")), account(jwt));
        }
        // Whatever else is added to the filter chain later fails closed here rather than open.
        throw new AccessDeniedException("A transaction needs a Keycloak token");
    }

    private static @Nullable UUID account(Jwt jwt) {
        @Nullable String account = jwt.getClaimAsString("account_id");
        if (account == null || account.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(account);
        } catch (IllegalArgumentException notAnAccountId) {
            // Keycloak writes the claim from a user attribute only wallet-auth-service sets; anything
            // else in it is refused rather than read as "no account", which would make the caller an
            // operator.
            throw new AccessDeniedException("The token's account_id is not an account id");
        }
    }
}
