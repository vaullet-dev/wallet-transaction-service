package dev.vaullet.transaction.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.transaction.support.StubServer.Request;
import dev.vaullet.transaction.support.StubServer.Response;
import dev.vaullet.transaction.support.Upstreams;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole stack: filter chain, authorisation, binding, validation, the rules, PostgreSQL, the error
 * bodies — and on the wire, a stand-in for the ledger and for Keycloak's token endpoint
 * ({@link Upstreams}), free to answer what a real ledger would only answer on a bad day.
 *
 * <p>The stand-in ledger behaves as the real one does where it matters: a hold is keyed on its
 * {@code Idempotency-Key}, so the same key always gets the same {@code reservation_id} back, and a
 * hold of 12 or more is funded 12 from BONUS and the rest from CASH, so allocations have an order to
 * keep.
 */
@IntegrationTest
@Import(Upstreams.class)
class TransactionApiIT {

    private static final String OPERATOR = "5a5a5a5a-0000-4000-8000-0000000000aa";
    private static final String END_USER = "e0e0e0e0-0000-4000-8000-0000000000ee";
    private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*\"([^\"]*)\"");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Upstreams.Stub upstream;

    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE transaction_allocations, transactions");
        upstream.ledger(TransactionApiIT::holds);
        upstream.forget();
    }

    // --- Callers -------------------------------------------------------------------------------

    /** An operator's backend on client credentials: both operator scopes, no person, no account. */
    private static JwtRequestPostProcessor operator() {
        return jwt().jwt(token -> token.subject(OPERATOR).claim("azp", "operator-backend"))
                .authorities(
                        new SimpleGrantedAuthority("SCOPE_transaction:write"),
                        new SimpleGrantedAuthority("SCOPE_transaction:read"));
    }

    /** An end user signed in through a client people sign in with: their own account in the token. */
    private static JwtRequestPostProcessor endUser(UUID account) {
        return jwt().jwt(token -> token.subject(END_USER).claim("azp", "bruno").claim("account_id", account.toString()))
                .authorities(
                        new SimpleGrantedAuthority("SCOPE_transaction:self"),
                        new SimpleGrantedAuthority("ROLE_END_USER"));
    }

    // --- Requests ------------------------------------------------------------------------------

    private ResultActions create(JwtRequestPostProcessor caller, String key, String body) throws Exception {
        return mvc.perform(post("/v1/transactions")
                .with(caller)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String debit(@Nullable UUID account, String amount) {
        return """
                {%s"type": "DEBIT", "amount": "%s", "currency": "EUR", "expires_in_seconds": 259200,
                 "reference": "bet-8891", "metadata": {"market": "match-winner"}}"""
                .formatted(account == null ? "" : "\"account_id\": \"" + account + "\", ", amount);
    }

    private static String credit(UUID account, String amount) {
        return """
                {"account_id": "%s", "type": "CREDIT", "amount": "%s", "currency": "EUR", "reference": "win-8891"}"""
                .formatted(account, amount);
    }

    private static String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.transaction_id");
    }

    private String statusOf(String transactionId) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE transaction_id = ?::uuid", String.class,
                transactionId);
    }

    // --- The stand-in ledger ---------------------------------------------------------------------

    /** The ledger on a good day: the hold, keyed on the Idempotency-Key, funded BONUS first. */
    private static Response holds(Request request) {
        if (!request.method().equals("POST") || !request.path().equals("/v1/reservations")) {
            return Response.problem(404, "RESOURCE_NOT_FOUND");
        }
        String key = request.header("Idempotency-Key");
        UUID reservation = UUID.nameUUIDFromBytes((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
        Matcher amount = AMOUNT.matcher(request.body());
        BigDecimal total = new BigDecimal(amount.find() ? amount.group(1) : "0");
        BigDecimal bonus = total.min(new BigDecimal("12"));
        String allocations = """
                {"bucket_id": "b0b0b0b0-0000-4000-8000-000000000001", "bucket_type": "BONUS", "amount": "%s"}"""
                .formatted(bonus.setScale(4).toPlainString());
        if (total.compareTo(bonus) > 0) {
            allocations += """
                    , {"bucket_id": "c0c0c0c0-0000-4000-8000-000000000002", "bucket_type": "CASH", "amount": "%s"}"""
                    .formatted(total.subtract(bonus).setScale(4).toPlainString());
        }
        return Response.json(201, """
                {"reservation_id": "%s", "amount": "%s", "state": "HELD", "expires_at": "2026-10-26T12:00:00Z",
                 "allocations": [%s]}""".formatted(reservation, total.setScale(4).toPlainString(), allocations));
    }

    // --- Tests ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("an operator's debit")
    class OperatorDebit {

        @Test
        @DisplayName("is held by the ledger and answered AUTHORIZED, with its hold and its buckets in order")
        void authorized() throws Exception {
            MvcResult result = create(operator(), "bet-8891", debit(account, "20.00"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", notNullValue()))
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andExpect(jsonPath("$.type", is("DEBIT")))
                    .andExpect(jsonPath("$.account_id", is(account.toString())))
                    .andExpect(jsonPath("$.amount", is("20.00")))
                    .andExpect(jsonPath("$.currency", is("EUR")))
                    .andExpect(jsonPath("$.reference", is("bet-8891")))
                    .andExpect(jsonPath("$.metadata.market", is("match-winner")))
                    .andExpect(jsonPath("$.reservation_id", notNullValue()))
                    .andExpect(jsonPath("$.expires_at", is("2026-10-26T12:00:00Z")))
                    .andExpect(jsonPath("$.allocations", hasSize(2)))
                    .andExpect(jsonPath("$.allocations[0].bucket_type", is("BONUS")))
                    .andExpect(jsonPath("$.allocations[0].amount", is("12.00")))
                    .andExpect(jsonPath("$.allocations[1].bucket_type", is("CASH")))
                    .andExpect(jsonPath("$.allocations[1].amount", is("8.00")))
                    .andExpect(jsonPath("$.decline_code").doesNotExist())
                    .andReturn();
            String transactionId = id(result);
            assertThat(result.getResponse().getHeader("Location")).endsWith("/v1/transactions/" + transactionId);

            // What the ledger was asked: this service's token, a key of the transaction's, the request.
            Request asked = upstream.ledgerRequests().getFirst();
            assertThat(asked.header("Authorization")).startsWith("Bearer stub-token-");
            assertThat(asked.header("Idempotency-Key")).isEqualTo("transaction:" + transactionId);
            assertThat(asked.body()).isEqualTo(
                    "{\"account_id\":\"" + account + "\",\"amount\":\"20.00\",\"expires_in_seconds\":259200}");
            // ...with a token of this service's own client, asking for the ledger's scope. Cached for
            // the context's life, so perhaps fetched for an earlier test: the last fetch is the one.
            assertThat(upstream.lastTokenRequest()).hasValueSatisfying(token -> assertThat(token.body())
                    .contains("grant_type=client_credentials")
                    .contains("client_id=wallet-transaction-service")
                    .contains("scope=ledger%3Awrite"));
        }

        @Test
        @DisplayName("repeated with the same key: the same transaction, and the ledger is not asked again")
        void replay() throws Exception {
            String first = id(create(operator(), "bet-1", debit(account, "20.00")).andReturn());
            upstream.forget();

            create(operator(), "bet-1", debit(account, "20.0"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transaction_id", is(first)))
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andExpect(jsonPath("$.allocations", hasSize(2)));
            assertThat(upstream.ledgerRequests()).isEmpty();
        }

        @Test
        @DisplayName("the same key with another request: IDEMPOTENCY_KEY_REUSED, naming the key's transaction")
        void key_reused() throws Exception {
            String first = id(create(operator(), "bet-2", debit(account, "20.00")).andReturn());

            create(operator(), "bet-2", debit(account, "25.00"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")))
                    .andExpect(jsonPath("$.transaction_id", is(first)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("refused for want of funds: 409 INSUFFICIENT_FUNDS, recorded DECLINED, and so on every replay")
        void insufficient_funds() throws Exception {
            upstream.ledger(request -> Response.json(409, """
                    {"code": "INSUFFICIENT_FUNDS", "detail": "available 5.0000 < requested 20.00"}"""));

            MvcResult refused = create(operator(), "bet-3", debit(account, "20.00"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")))
                    .andExpect(jsonPath("$.detail", is("available 5.0000 < requested 20.00")))
                    .andExpect(jsonPath("$.transaction_id", notNullValue()))
                    .andReturn();
            String transactionId = id(refused);

            mvc.perform(get("/v1/transactions/{id}", transactionId).with(operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("DECLINED")))
                    .andExpect(jsonPath("$.decline_code", is("INSUFFICIENT_FUNDS")))
                    .andExpect(jsonPath("$.reservation_id").doesNotExist())
                    .andExpect(jsonPath("$.allocations", hasSize(0)));

            // Funds arrive; a replay still answers with the refusal, and asks nobody.
            upstream.ledger(TransactionApiIT::holds);
            upstream.forget();
            create(operator(), "bet-3", debit(account, "20.00"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")))
                    .andExpect(jsonPath("$.transaction_id", is(transactionId)));
            assertThat(upstream.ledgerRequests()).isEmpty();
        }

        @Test
        @DisplayName("an account the ledger does not know: 404 RESOURCE_NOT_FOUND, recorded DECLINED")
        void unknown_account() throws Exception {
            upstream.ledger(request -> Response.json(404, """
                    {"code": "RESOURCE_NOT_FOUND", "detail": "Account was not found"}"""));

            String transactionId = id(create(operator(), "bet-4", debit(account, "20.00"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")))
                    .andReturn());
            assertThat(statusOf(transactionId)).isEqualTo("DECLINED");
        }

        @Test
        @DisplayName("a hold longer than the ledger allows: 422 HOLD_TTL_TOO_LONG, recorded DECLINED")
        void hold_too_long() throws Exception {
            upstream.ledger(request -> Response.json(422, """
                    {"code": "HOLD_TTL_TOO_LONG", "detail": "259200s exceeds max_hold_seconds 86400"}"""));

            create(operator(), "bet-5", debit(account, "20.00"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("HOLD_TTL_TOO_LONG")))
                    .andExpect(jsonPath("$.detail", is("259200s exceeds max_hold_seconds 86400")));
        }

        @Test
        @DisplayName("the ledger busy: 503, the debit stays PENDING, and a retry with the same key holds it")
        void ledger_busy_then_retry() throws Exception {
            upstream.ledger(request -> Response.problem(503, "ACCOUNT_BUSY"));

            String pending = id(create(operator(), "bet-6", debit(account, "20.00"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code", is("UPSTREAM_UNAVAILABLE")))
                    .andReturn());
            // The body names the PENDING debit, so a caller that has lost its key can still find it.
            assertThat(jdbc.queryForObject("SELECT transaction_id::text || ' ' || status FROM transactions",
                    String.class)).isEqualTo(pending + " PENDING");
            // The lease was given back, so the retry need not wait for it.
            assertThat(jdbc.queryForObject("SELECT attempt_until FROM transactions", Object.class)).isNull();

            upstream.ledger(TransactionApiIT::holds);
            String retried = id(create(operator(), "bet-6", debit(account, "20.00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andReturn());

            // One transaction, asked of the ledger twice under one key: one hold, whatever the first did.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(1);
            assertThat(upstream.ledgerRequests()).hasSize(2).extracting(r -> r.header("Idempotency-Key"))
                    .containsOnly("transaction:" + retried);
            assertThat(retried).isEqualTo(pending);
        }

        @Test
        @DisplayName("the ledger silent past its timeout: 503 and PENDING, and the retry picks it up")
        void ledger_timeout_then_retry() throws Exception {
            upstream.ledger(request -> holds(request).after(Duration.ofMillis(1500)));

            create(operator(), "bet-7", debit(account, "20.00"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code", is("UPSTREAM_UNAVAILABLE")))
                    .andExpect(jsonPath("$.transaction_id", notNullValue()));
            assertThat(jdbc.queryForObject("SELECT status FROM transactions", String.class)).isEqualTo("PENDING");

            upstream.ledger(TransactionApiIT::holds);
            create(operator(), "bet-7", debit(account, "20.00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")));
        }

        @Test
        @DisplayName("repeated while the first call is still waiting for the ledger: 409 REQUEST_IN_PROGRESS")
        void in_progress() throws Exception {
            CountDownLatch ledgerAsked = new CountDownLatch(1);
            CountDownLatch answer = new CountDownLatch(1);
            upstream.ledger(request -> {
                ledgerAsked.countDown();
                try {
                    answer.await(900, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return holds(request);
            });

            try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<MvcResult> first =
                        caller.submit(() -> create(operator(), "bet-8", debit(account, "20.00")).andReturn());
                assertThat(ledgerAsked.await(5, TimeUnit.SECONDS)).isTrue();

                create(operator(), "bet-8", debit(account, "20.00"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code", is("REQUEST_IN_PROGRESS")))
                        .andExpect(jsonPath("$.transaction_id", notNullValue()));

                answer.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            }
            assertThat(upstream.ledgerRequests()).hasSize(1);
        }

        @Test
        @DisplayName("a ledger that answers 201 about something else: 500, and the debit stays PENDING")
        void not_the_hold() throws Exception {
            upstream.ledger(request -> Response.json(201, "{\"wallet_id\": \"" + UUID.randomUUID() + "\"}"));

            create(operator(), "bet-9", debit(account, "20.00"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")));
            assertThat(jdbc.queryForObject("SELECT status FROM transactions", String.class)).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("an operator's credit")
    class OperatorCredit {

        @Test
        @DisplayName("is recorded AUTHORIZED without asking the ledger: nothing to hold")
        void recorded() throws Exception {
            create(operator(), "win-1", credit(account, "50"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type", is("CREDIT")))
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andExpect(jsonPath("$.amount", is("50.00")))
                    .andExpect(jsonPath("$.reservation_id").doesNotExist())
                    .andExpect(jsonPath("$.expires_at").doesNotExist())
                    .andExpect(jsonPath("$.allocations", hasSize(0)))
                    .andExpect(jsonPath("$.metadata", is(Map.of())));
            assertThat(upstream.ledgerRequests()).isEmpty();
            assertThat(upstream.tokenRequests()).isEmpty();
        }

        @Test
        @DisplayName("asking a credit for a hold's expiry is VALIDATION_FAILED: there is no hold")
        void no_expiry() throws Exception {
            create(operator(), "win-2", """
                    {"account_id": "%s", "type": "CREDIT", "amount": "50", "currency": "EUR", "expires_in_seconds": 60}"""
                    .formatted(account))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }
    }

    @Nested
    @DisplayName("requests refused before anything is recorded")
    class Refused {

        @Test
        @DisplayName("another currency: 422 CURRENCY_MISMATCH")
        void currency() throws Exception {
            create(operator(), "k-1", debit(account, "20.00").replace("\"EUR\"", "\"USD\""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
        }

        @Test
        @DisplayName("an amount finer than a cent: VALIDATION_FAILED")
        void finer_than_the_minor_unit() throws Exception {
            create(operator(), "k-2", debit(account, "20.001"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.detail", is("amount: EUR has 2 decimal places, so 20.001 is not an amount of it")));
        }

        @Test
        @DisplayName("fields missing or out of range: VALIDATION_FAILED naming each, in snake_case")
        void fields() throws Exception {
            create(operator(), "k-3", """
                    {"account_id": "%s", "type": "WAGER", "amount": "0", "currency": "eur", "expires_in_seconds": 0}"""
                    .formatted(account))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder(
                            "amount", "currency", "expires_in_seconds", "type")));
        }

        @Test
        @DisplayName("capture: true is VALIDATION_FAILED, not a hold in silence; false is the request as it is")
        void capture() throws Exception {
            create(operator(), "k-8", debit(account, "20.00").replace("\"type\"", "\"capture\": true, \"type\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.errors[0].field", is("capture")));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();

            create(operator(), "k-9", debit(account, "20.00").replace("\"type\"", "\"capture\": false, \"type\""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")));
        }

        @Test
        @DisplayName("an operator's request without account_id: VALIDATION_FAILED, the token names none")
        void operator_without_account() throws Exception {
            create(operator(), "k-4", debit(null, "20.00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.detail", startsWith("account_id: the request must name the account.")));
        }

        @Test
        @DisplayName("an Idempotency-Key over 255 characters: VALIDATION_FAILED")
        void key_too_long() throws Exception {
            create(operator(), "k".repeat(256), debit(account, "20.00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        }

        @Test
        @DisplayName("no Idempotency-Key at all: 400")
        void no_key() throws Exception {
            mvc.perform(post("/v1/transactions")
                            .with(operator())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(debit(account, "20.00")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("no transaction:write: 403, for a debit and a credit alike")
        void no_scope() throws Exception {
            var readOnly = jwt().jwt(token -> token.subject(OPERATOR))
                    .authorities(new SimpleGrantedAuthority("SCOPE_transaction:read"));
            create(readOnly, "k-5", debit(account, "20.00")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
            create(readOnly, "k-6", credit(account, "20.00")).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
        }

        @Test
        @DisplayName("a token that names nobody: 403, a key needs a caller to belong to")
        void no_subject() throws Exception {
            var nobody = jwt().jwt(token -> token.claims(claims -> claims.remove("sub")))
                    .authorities(new SimpleGrantedAuthority("SCOPE_transaction:write"));
            create(nobody, "k-7", debit(account, "20.00")).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("an end user")
    class EndUser {

        @Test
        @DisplayName("debits their own account, the one their token names, without naming it")
        void own_debit() throws Exception {
            create(endUser(account), "buy-1", debit(null, "5.00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.account_id", is(account.toString())))
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andExpect(jsonPath("$.allocations", hasSize(1)));
            assertThat(jdbc.queryForMap("SELECT requested_by, client_id FROM transactions"))
                    .containsEntry("requested_by", END_USER)
                    .containsEntry("client_id", "bruno");
        }

        @Test
        @DisplayName("...whatever the case of the account id in their token")
        void own_debit_upper_case_claim() throws Exception {
            var shouting = jwt().jwt(token -> token.subject(END_USER)
                            .claim("account_id", account.toString().toUpperCase(Locale.ROOT)))
                    .authorities(
                            new SimpleGrantedAuthority("SCOPE_transaction:self"),
                            new SimpleGrantedAuthority("ROLE_END_USER"));
            String created = id(create(shouting, "buy-7", debit(null, "5.00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.account_id", is(account.toString())))
                    .andReturn());
            mvc.perform(get("/v1/transactions/{id}", created).with(shouting)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("...or naming it, which is the same request")
        void own_debit_named() throws Exception {
            String first = id(create(endUser(account), "buy-2", debit(null, "5.00")).andReturn());
            create(endUser(account), "buy-2", debit(account, "5.00"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transaction_id", is(first)));
        }

        @Test
        @DisplayName("cannot debit another account, credit their own, or act without END_USER")
        void refused() throws Exception {
            create(endUser(account), "buy-3", debit(UUID.randomUUID(), "5.00"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
            create(endUser(account), "buy-4", credit(account, "5.00")).andExpect(status().isForbidden());

            var staff = jwt().jwt(token -> token.subject(END_USER).claim("account_id", account.toString()))
                    .authorities(
                            new SimpleGrantedAuthority("SCOPE_transaction:self"),
                            new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));
            create(staff, "buy-5", debit(null, "5.00")).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
            assertThat(upstream.ledgerRequests()).isEmpty();
        }

        @Test
        @DisplayName("reads their own transactions, and finds nobody else's: 404, as for an id that does not exist")
        void reads_own() throws Exception {
            String own = id(create(endUser(account), "buy-6", debit(null, "5.00")).andReturn());
            String others = id(create(operator(), "bet-10", debit(UUID.randomUUID(), "5.00")).andReturn());

            mvc.perform(get("/v1/transactions/{id}", own).with(endUser(account)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transaction_id", is(own)));
            mvc.perform(get("/v1/transactions/{id}", others).with(endUser(account)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
        }

        @Test
        @DisplayName("an Idempotency-Key is the caller's own: another caller's identical key is another transaction")
        void keys_are_per_caller() throws Exception {
            String users = id(create(endUser(account), "same-key", debit(null, "5.00")).andReturn());
            String operators = id(create(operator(), "same-key", debit(account, "5.00")).andReturn());

            assertThat(users).isNotEqualTo(operators);
            assertThat(upstream.ledgerRequests()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("reading a transaction as an operator")
    class OperatorRead {

        @Test
        @DisplayName("transaction:read reads any; without it, 403; an id that does not exist, 404")
        void read() throws Exception {
            String transactionId = id(create(operator(), "bet-11", debit(account, "20.00")).andReturn());

            mvc.perform(get("/v1/transactions/{id}", transactionId).with(operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transaction_id", is(transactionId)))
                    .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                    .andExpect(jsonPath("$.allocations[0].bucket_type", is("BONUS")))
                    .andExpect(jsonPath("$.created_at", notNullValue()))
                    .andExpect(jsonPath("$.updated_at", notNullValue()));

            var writeOnly = jwt().jwt(token -> token.subject(OPERATOR))
                    .authorities(new SimpleGrantedAuthority("SCOPE_transaction:write"));
            mvc.perform(get("/v1/transactions/{id}", transactionId).with(writeOnly)).andExpect(status().isForbidden());

            mvc.perform(get("/v1/transactions/{id}", UUID.randomUUID()).with(operator()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")))
                    .andExpect(jsonPath("$.detail", endsWith("was not found")))
                    // Not a transaction this caller recorded, so no transaction_id: it would name nothing.
                    .andExpect(jsonPath("$.transaction_id").doesNotExist());
        }
    }

    @Test
    @DisplayName("the API document lists both operations")
    void api_docs() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/transactions'].post", notNullValue()))
                .andExpect(jsonPath("$.paths['/v1/transactions/{id}'].get", notNullValue()))
                .andExpect(jsonPath("$.components.schemas.Transaction.properties.transaction_id", notNullValue()));
    }

}
