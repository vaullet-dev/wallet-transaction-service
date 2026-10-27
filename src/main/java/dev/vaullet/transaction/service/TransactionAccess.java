package dev.vaullet.transaction.service;

/**
 * The {@code @PreAuthorize} rules of {@link TransactionService}, each written once.
 *
 * <p>Constants rather than a bean the expressions call, as in wallet-auth-service's
 * {@code AccountAccess}: every rule stays readable on the method it guards and is checked by the
 * compiler, since an annotation value has to be a compile-time constant.
 *
 * <p>Two kinds of caller, told apart by their scopes (gitops {@code realm/15-vaullet-client-scopes.yaml}):
 *
 * <ul>
 *   <li><b>An operator's backend</b>, on client credentials, with {@code transaction:write} and
 *       {@code transaction:read}. No person is behind its token, so on this side the scope is the
 *       permission, as {@code ledger:reserve} is on the ledger, and the scopes go only to clients that
 *       are such backends — never to one people sign in with, where every user's token would carry
 *       them.
 *   <li><b>An end user</b>, for their own account: {@code transaction:self}, the {@code END_USER}
 *       role, and the account their token names. The scope alone would not do: Keycloak puts a client
 *       scope in the token of everyone who signs in through the client, staff included, so the role
 *       says who is asking and the token's {@code account_id} says for which account.
 * </ul>
 */
final class TransactionAccess {

    /** An operator's backend recording a transaction on any account, a debit or a credit. */
    static final String WRITE = "hasAuthority('SCOPE_transaction:write')";

    /**
     * An end user's own debit, and nothing else: no credit — money into an account is the operator's
     * to give — and no account but the one their token names.
     *
     * <p>{@code hasRole('END_USER')} first, deliberately: SpEL's {@code and} stops at the first false,
     * and only a Keycloak token has the {@code token.claims} the last clause reads. An end user always
     * signs in with one. The account ids are compared as {@code Callers} reads them, without regard to
     * case: a UUID is the same UUID in capitals, and a missing claim is simply not equal.
     */
    static final String SELF = "hasAuthority('SCOPE_transaction:self') and hasRole('END_USER')"
            + " and #request.type().name() == 'DEBIT'"
            + " and #request.accountId().toString().equalsIgnoreCase(authentication.token.claims['account_id'])";

    /** An operator's backend reading any transaction. */
    static final String READ = "hasAuthority('SCOPE_transaction:read')";

    /**
     * An end user reading a transaction on their own account. The method looks the transaction up by
     * id <em>and</em> account, so somebody else's is not found rather than forbidden: a 403 would
     * confirm that the id exists.
     */
    static final String SELF_READ = "hasAuthority('SCOPE_transaction:self') and hasRole('END_USER')"
            + " and #accountId.toString().equalsIgnoreCase(authentication.token.claims['account_id'])";

    private TransactionAccess() {}
}
