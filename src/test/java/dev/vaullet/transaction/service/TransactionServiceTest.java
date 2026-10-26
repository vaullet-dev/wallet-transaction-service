package dev.vaullet.transaction.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.vaullet.transaction.config.TransactionProperties;
import dev.vaullet.transaction.dao.LedgerReservations;
import dev.vaullet.transaction.dao.TransactionRepository;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The deployment's currency is checked when the service is built, so a deployment that got it wrong
 * fails at startup with the property named, not at its first request. Everything else the service
 * does is {@code TransactionApiIT}'s, against PostgreSQL and a stand-in ledger.
 */
class TransactionServiceTest {

    private static TransactionProperties properties(@Nullable String currency, @Nullable Integer minorUnits) {
        return new TransactionProperties(
                currency,
                minorUnits,
                new TransactionProperties.Keycloak("http://keycloak.invalid", "vaullet", "wallet-transaction-service",
                        "unused", Duration.ofSeconds(1)),
                new TransactionProperties.Ledger("http://ledger.invalid", Duration.ofSeconds(1)));
    }

    private static TransactionService service(TransactionProperties properties) {
        return new TransactionService(mock(TransactionRepository.class), new LedgerReservations(properties),
                mock(TransactionTemplate.class), properties);
    }

    @Test
    @DisplayName("an ISO 4217 currency, with its own minor unit or one the deployment sets, is all it needs")
    void starts() {
        assertThatCode(() -> service(properties("EUR", null))).doesNotThrowAnyException();
        assertThatCode(() -> service(properties("JPY", null))).doesNotThrowAnyException();
        assertThatCode(() -> service(properties("EUR", 4))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no currency: refused, naming the property and the ledger's twin")
    void no_currency() {
        assertThatThrownBy(() -> service(properties(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRANSACTION_CURRENCY")
                .hasMessageContaining("LEDGER_CURRENCY");
        assertThatThrownBy(() -> service(properties(" ", null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("not a currency, or one without a minor unit nobody set, or more places than NUMERIC(20,4) keeps")
    void wrong_currency() {
        assertThatThrownBy(() -> service(properties("EURO", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an ISO 4217 code");
        // XXX is ISO 4217's "no currency": it exists, and has no minor unit.
        assertThatThrownBy(() -> service(properties("XXX", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRANSACTION_MINOR_UNITS");
        assertThatThrownBy(() -> service(properties("EUR", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 to 4");
    }

    @Test
    @DisplayName("the ledger's and Keycloak's addresses are LedgerReservations', checked as it is built")
    void no_ledger() {
        TransactionProperties noLedger = new TransactionProperties("EUR", null,
                new TransactionProperties.Keycloak("http://keycloak.invalid", "vaullet", "wallet-transaction-service",
                        "unused", Duration.ofSeconds(1)),
                new TransactionProperties.Ledger(null, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> new LedgerReservations(noLedger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEDGER_URL");
    }
}
