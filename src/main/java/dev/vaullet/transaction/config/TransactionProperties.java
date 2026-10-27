package dev.vaullet.transaction.config;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * This service's own settings under {@code transaction.*}.
 *
 * <p>Nothing here has a default that would let a deployment start half-configured: without a
 * currency the service cannot tell a request in the wrong one, and without Keycloak and the ledger
 * it cannot hold a cent. {@code TransactionService} and {@code LedgerReservations} refuse to be
 * built without what they need, so a deployment that forgot one fails at startup with the property
 * named, not at its first request.
 *
 * @param currency the deployment's currency, ISO 4217 ({@code TRANSACTION_CURRENCY}). The ledger's
 *     {@code LEDGER_CURRENCY}, and the two must agree: ADR-004 makes the currency a deployment
 *     constant, and a request in any other is {@code CURRENCY_MISMATCH} here, before the ledger is
 *     asked.
 * @param minorUnits decimal places of the currency's minor unit ({@code TRANSACTION_MINOR_UNITS}).
 *     Unset means ISO 4217's: 2 for EUR, 0 for JPY, 3 for KWD. The ledger's
 *     {@code LEDGER_MINOR_UNITS}, when that is set.
 * @param keycloak where this service gets its own token for the ledger.
 * @param ledger where the ledger is.
 */
@ConfigurationProperties("transaction")
public record TransactionProperties(
        @Nullable String currency,
        @Nullable Integer minorUnits,
        @DefaultValue Keycloak keycloak,
        @DefaultValue Ledger ledger) {

    /**
     * @param url Keycloak's base URL, relative path included: the in-cluster Service,
     *     {@code http://keycloak-keycloakx-http.keycloak.svc/auth}. The token endpoint is under it.
     * @param realm the realm this service's client lives in.
     * @param clientId the confidential client this service authenticates as. It offers
     *     {@code ledger:reserve}, which every token this service asks for names.
     * @param clientSecret that client's secret, from OpenBao through External Secrets.
     * @param timeout connect and read timeout for every call to Keycloak.
     */
    public record Keycloak(
            @Nullable String url,
            @DefaultValue("vaullet") String realm,
            @DefaultValue("wallet-transaction-service") String clientId,
            @Nullable String clientSecret,
            @DefaultValue("5s") Duration timeout) {

        /** The secret stays out of logs and error messages, whoever ends up printing this. */
        @Override
        public String toString() {
            boolean secretSet = clientSecret != null && !clientSecret.isBlank();
            return "Keycloak[url=" + url
                    + ", realm=" + realm
                    + ", clientId=" + clientId
                    + ", clientSecret=" + (secretSet ? "<redacted>" : "<unset>")
                    + ", timeout=" + timeout + "]";
        }
    }

    /**
     * @param url the ledger's in-cluster Service, {@code http://ledger-service.wallet-ledger.svc}.
     *     There is no other way in: the ledger has no public route (ADR-008).
     * @param timeout connect and read timeout for every call to the ledger. Together with
     *     Keycloak's, it bounds how long one call to the ledger can take, and so how long a retry
     *     waits before it may take over a call that never answered.
     */
    public record Ledger(@Nullable String url, @DefaultValue("5s") Duration timeout) {}
}
