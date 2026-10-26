package dev.vaullet.transaction.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import dev.vaullet.transaction.dao.LedgerReservations.Allocation;
import dev.vaullet.transaction.dao.LedgerReservations.Held;
import dev.vaullet.transaction.dao.LedgerReservations.Outcome;
import dev.vaullet.transaction.dao.LedgerReservations.Refused;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What each answer of the ledger's {@code POST /v1/reservations} means, without a ledger.
 *
 * <p>Four outcomes and nothing else: the money is held, the ledger refused this request, or the
 * attempt failed — and a failure is either one that time fixes ({@link UpstreamUnavailableException})
 * or one a person has to ({@link IllegalStateException}). {@code TransactionApiIT} sends the same
 * answers over the wire.
 */
class LedgerReservationsTest {

    private static final BigDecimal AMOUNT = new BigDecimal("20.00");
    private static final String CLIENT = "wallet-transaction-service";
    private static final String RESERVATION = "5e1f0c2a-9b8d-4e7f-a6c5-b4d3e2f1a0b9";
    private static final String BONUS = "b0b0b0b0-0000-4000-8000-000000000001";
    private static final String CASH = "c0c0c0c0-0000-4000-8000-000000000002";

    private static Outcome answered(int status, String body) {
        return LedgerReservations.interpret(status, body, AMOUNT, CLIENT);
    }

    /** A problem document with this code, as every Vaullet service sends one; no body for none. */
    private static Outcome problem(int status, @Nullable String code) {
        return answered(status, code == null ? "" : """
                {"type": "https://docs.vaullet.dev/errors/stub", "status": %d, "code": "%s", "detail": "stub detail"}"""
                .formatted(status, code));
    }

    /** The ledger's ReservationResponse, snake_case, as its ReservationController sends it. */
    private static String hold(String amount, String state, String allocations) {
        return """
                {"reservation_id": "%s", "amount": "%s", "state": "%s", "expires_at": "2026-10-23T18:00:00Z",
                 "allocations": [%s]}""".formatted(RESERVATION, amount, state, allocations);
    }

    private static final String SPLIT = """
            {"bucket_id": "%s", "bucket_type": "BONUS", "amount": "12.0000"},
            {"bucket_id": "%s", "bucket_type": "CASH", "amount": "8.0000"}""".formatted(BONUS, CASH);

    @Nested
    @DisplayName("the money is held")
    class HeldMoney {

        @Test
        @DisplayName("201 with a hold of the amount asked for: the hold, its expiry, and its buckets in the ledger's order")
        void held() {
            assertThat(answered(201, hold("20.0000", "HELD", SPLIT))).isEqualTo(new Held(
                    UUID.fromString(RESERVATION),
                    "HELD",
                    Instant.parse("2026-10-23T18:00:00Z"),
                    List.of(
                            new Allocation(UUID.fromString(BONUS), "BONUS", new BigDecimal("12.0000")),
                            new Allocation(UUID.fromString(CASH), "CASH", new BigDecimal("8.0000")))));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"SETTLED", "RELEASED", "EXPIRED"})
        @DisplayName("a replay's hold in a later state is still the hold: the service decides what it means")
        void later_states(String state) {
            assertThat(answered(201, hold("20.00", state, SPLIT))).isInstanceOfSatisfying(Held.class,
                    held -> assertThat(held.state()).isEqualTo(state));
        }
    }

    @Nested
    @DisplayName("a 201 that is not the hold asked for — refused, because AUTHORIZED is never asked about again")
    class NotTheHold {

        @Test
        @DisplayName("another amount")
        void another_amount() {
            assertThatThrownBy(() -> answered(201, hold("19.99", "HELD", """
                    {"bucket_id": "%s", "bucket_type": "CASH", "amount": "19.99"}""".formatted(CASH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("holds 19.99, not the 20.00 asked for")
                    .hasMessageContaining("LEDGER_URL");
        }

        @Test
        @DisplayName("allocations that do not add up to it")
        void allocations_short() {
            assertThatThrownBy(() -> answered(201, hold("20.00", "HELD", """
                    {"bucket_id": "%s", "bucket_type": "CASH", "amount": "8.00"}""".formatted(CASH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("add up to 8.00");
        }

        @Test
        @DisplayName("no allocations at all")
        void no_allocations() {
            assertThatThrownBy(() -> answered(201, hold("20.00", "HELD", "")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no allocations");
        }

        @Test
        @DisplayName("a state the ledger does not have")
        void unknown_state() {
            assertThatThrownBy(() -> answered(201, hold("20.00", "PENDING", SPLIT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'PENDING'");
        }

        @Test
        @DisplayName("a body that is something else entirely: another service's page, or nothing")
        void not_a_hold() {
            assertThatThrownBy(() -> answered(201, "<html>It works!</html>"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a JSON object");
            assertThatThrownBy(() -> answered(201, "{\"wallet_id\": \"" + RESERVATION + "\"}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reservation_id is (none)");
        }
    }

    @Nested
    @DisplayName("the ledger refused this request")
    class Refusals {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"409, INSUFFICIENT_FUNDS", "404, RESOURCE_NOT_FOUND", "422, HOLD_TTL_TOO_LONG"})
        @DisplayName("its code and detail, for the transaction to keep")
        void refused(int status, String code) {
            assertThat(problem(status, code)).isEqualTo(new Refused(code, "stub detail"));
        }

        @Test
        @DisplayName("a detail longer than 500 characters is kept short")
        void long_detail() {
            String detail = "x".repeat(800);
            assertThat(answered(409, "{\"code\": \"INSUFFICIENT_FUNDS\", \"detail\": \"" + detail + "\"}"))
                    .isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.detail()).hasSize(500));
        }

        @Test
        @DisplayName("...but only the ledger's codes at their statuses: a 404 without one is a wrong URL")
        void wrong_url() {
            assertThatThrownBy(() -> problem(404, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unexpected HTTP 404 from the ledger on POST /v1/reservations");
            assertThatThrownBy(() -> problem(409, "WALLET_CLOSED")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("the attempt failed")
    class Failures {

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {429, 500, 502, 503, 504})
        @DisplayName("not now: a rate limit, or a ledger unwell or busy — the debit stays PENDING for a retry")
        void not_now(int status) {
            assertThatThrownBy(() -> problem(status, status == 503 ? "ACCOUNT_BUSY" : null))
                    .isInstanceOf(UpstreamUnavailableException.class);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {401, 403})
        @DisplayName("the token is not one the ledger accepts: configuration, named as such")
        void token_refused(int status) {
            assertThatThrownBy(() -> problem(status, "ACCESS_DENIED"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("client '" + CLIENT + "'")
                    .hasMessageContaining("ledger:write");
        }

        @Test
        @DisplayName("a request the ledger calls invalid: the two services disagree about the contract")
        void contract() {
            assertThatThrownBy(() -> problem(400, "VALIDATION_FAILED"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 400 VALIDATION_FAILED (stub detail)");
        }
    }
}
