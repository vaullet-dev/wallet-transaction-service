package dev.vaullet.transaction.dao;

import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import dev.vaullet.transaction.config.TransactionProperties;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger's {@code POST /v1/reservations}: hold an amount against an account (ADR-004).
 *
 * <p>A DAO in the sense wallet-auth-service's {@code LedgerWallets} is one: it knows the wire format
 * and the status codes, and takes no decisions. What a refusal means for a transaction, and what a
 * caller is told, are {@code TransactionService}'s. Nothing HTTP-shaped leaves this class: callers
 * get an {@link Outcome}; a ledger or Keycloak that did not answer, or answered "not now", arrives as
 * {@link UpstreamUnavailableException}; and an answer that a retry will not change, because the two
 * services disagree about the contract or the configuration is wrong, as
 * {@link IllegalStateException}.
 *
 * <p><b>The contract</b> (wallet-ledger-service README, "The API"; its {@code ReservationController}):
 * a bearer token carrying {@code ledger:write}, an {@code Idempotency-Key}, and a JSON body
 * {@code {"account_id", "amount", "expires_in_seconds"?}}. {@code 201} with the hold, whether this
 * call placed it or an earlier one with the same key did: the ledger replays a key's hold as it is
 * now, which may be {@code EXPIRED} or {@code RELEASED} by then. Refusals are problem+json with a
 * {@code code}: {@code 409 INSUFFICIENT_FUNDS}, {@code 404 RESOURCE_NOT_FOUND} for an account it does
 * not know, {@code 422 HOLD_TTL_TOO_LONG}, and {@code 503 ACCOUNT_BUSY} for a lock it could not take
 * in time.
 *
 * <p><b>Only that {@code 201} is a yes,</b> and only when its body is a hold of the amount asked for,
 * drawn from buckets that add up to it. A transaction marked AUTHORIZED is never asked about again, so
 * a {@code LEDGER_URL} that names something else must fail every attempt, loudly, rather than
 * authorize transactions with no money behind them.
 *
 * <p><b>The token</b> is this service's own client's, {@code wallet-transaction-service}, asking for
 * the optional scope {@code ledger:write}. Never the caller's token: ADR-008 has the ledger trust this
 * service, which has already decided who may ask, and a user's token is never a way to the ledger. A
 * {@code 401} drops the token from the cache, so the next attempt asks Keycloak for another.
 */
@Repository
@Profile("!migrate")
public class LedgerReservations {

    /** What the ledger checks on every write (wallet-ledger-service README, "Security"). */
    public static final String SCOPE = "ledger:write";

    private static final String UPSTREAM = "ledger";
    private static final String CALL = "POST /v1/reservations";

    /** The ledger's states for a hold, all of which a replay can answer with. */
    private static final Set<String> STATES = Set.of("HELD", "SETTLED", "RELEASED", "EXPIRED");

    /** A refusal's detail is kept and shown again on a replay, so it is kept short. */
    private static final int DETAIL_LIMIT = 500;

    private static final JsonMapper JSON = JsonMapper.shared();

    /** What the ledger answered about one hold. */
    public sealed interface Outcome permits Held, Refused {}

    /**
     * The ledger holds the money, or held it: placed by this call or by an earlier one with the same
     * key, in the state it is in now.
     *
     * @param state the ledger's: {@code HELD}, {@code SETTLED}, {@code RELEASED} or {@code EXPIRED}
     * @param allocations the buckets the hold draws on, in the ledger's order
     */
    public record Held(UUID reservationId, String state, Instant expiresAt, List<Allocation> allocations)
            implements Outcome {}

    /** One bucket's part of a hold. */
    public record Allocation(UUID bucketId, String bucketType, BigDecimal amount) {}

    /**
     * The ledger refused this hold, for a reason about this request rather than about the two
     * services: {@code INSUFFICIENT_FUNDS}, {@code RESOURCE_NOT_FOUND} or {@code HOLD_TTL_TOO_LONG}.
     *
     * @param detail the ledger's own explanation, at most {@value #DETAIL_LIMIT} characters
     */
    public record Refused(String code, String detail) implements Outcome {}

    private final RestClient ledger;
    private final KeycloakTokens tokens;
    private final String clientId;
    private final Duration longestReserve;

    /** Public so an integration test can point one at stand-ins for the ledger and Keycloak. */
    public LedgerReservations(TransactionProperties properties) {
        TransactionProperties.Keycloak keycloak = properties.keycloak();
        TransactionProperties.Ledger settings = properties.ledger();
        String ledgerUrl = required(settings.url(), "transaction.ledger.url (LEDGER_URL)");
        String keycloakUrl = required(keycloak.url(), "transaction.keycloak.url (KEYCLOAK_URL)");
        String secret = required(keycloak.clientSecret(), "transaction.keycloak.client-secret (KEYCLOAK_CLIENT_SECRET)");
        this.clientId = keycloak.clientId();
        // One fetch of a token: its connect timeout, then its read timeout.
        Duration oneFetch = keycloak.timeout().multipliedBy(2);
        this.tokens = new KeycloakTokens(client(keycloakUrl, keycloak.timeout()), keycloak.realm(), clientId, secret,
                SCOPE, oneFetch);
        this.ledger = client(ledgerUrl, settings.timeout());
        // A token may have to be waited for, while another request fetches one, and then fetched;
        // then the call itself, bounded by its connect timeout and its read timeout, separately.
        this.longestReserve = oneFetch.multipliedBy(2).plus(settings.timeout().multipliedBy(2));
    }

    /**
     * Ask the ledger to hold this amount against the account.
     *
     * @param expiresInSeconds how long the hold may stay uncaptured; null is the ledger's default
     * @param idempotencyKey the ledger's key for this hold. The same key always answers with the
     *     same hold, so a call repeated after a timeout cannot hold the money twice.
     * @throws UpstreamUnavailableException the ledger or Keycloak did not answer, or answered that it
     *     cannot now — a retry later may well succeed
     * @throws IllegalStateException the ledger or Keycloak refused this service, or answered something
     *     nobody expected — a person has to look before a retry can succeed
     */
    public Outcome reserve(UUID accountId, BigDecimal amount, @Nullable Integer expiresInSeconds,
            String idempotencyKey) {
        String token = tokens.bearer();
        // Built by hand, like LedgerWallets' in wallet-auth-service: a UUID, a decimal and an integer,
        // none of which needs escaping. The amount goes as a string, as ADR-011 has every service
        // send money, and the snake_case the ledger reads is on the page.
        String body = "{\"account_id\":\"" + accountId + "\",\"amount\":\"" + amount.toPlainString() + "\""
                + (expiresInSeconds == null ? "" : ",\"expires_in_seconds\":" + expiresInSeconds) + "}";
        try {
            return ledger.post()
                    .uri("/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(body)
                    .exchangeForRequiredValue((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            tokens.invalidate(token);
                        }
                        return interpret(status, read(response), amount, clientId);
                    });
        } catch (RestClientException e) {
            // Refused, reset, timed out, or a name that does not resolve: the ledger is not there.
            throw new UpstreamUnavailableException(UPSTREAM, e);
        }
    }

    /**
     * The longest one {@link #reserve} can take: the wait for another request's token and this one's
     * fetch, then the call, each bounded by its connect timeout and its read timeout. A debit's
     * attempt lease is built on it.
     */
    public Duration longestReserve() {
        return longestReserve;
    }

    /**
     * What one answer from {@code POST /v1/reservations} means. Static and given only what the wire
     * carried, so {@code LedgerReservationsTest} pins every case without a ledger.
     *
     * <ul>
     *   <li>{@code 201} with a hold of the amount asked for, in a state the ledger has, drawn from
     *       buckets that add up to it: {@link Held}. Nothing else is that: not a {@code 200}, which
     *       the ledger does not send, and not a {@code 201} about anything else.
     *   <li>{@code 409 INSUFFICIENT_FUNDS}, {@code 404 RESOURCE_NOT_FOUND}, {@code 422
     *       HOLD_TTL_TOO_LONG}: {@link Refused}, an answer about this request.
     *   <li>{@code 401}, {@code 403}: the token is not one the ledger accepts — the scope is missing,
     *       or the ledger trusts another issuer. Configuration, so not the 503 kind of failure.
     *   <li>{@code 429} and {@code 5xx}, {@code 503 ACCOUNT_BUSY} among them: not now.
     *   <li>Anything else, {@code 400 VALIDATION_FAILED} included: the two services disagree about
     *       the contract, or the URL names something else.
     * </ul>
     *
     * @param body the start of the answer's body: the hold, or a problem document
     * @param amount the amount asked for, which a {@code 201} must hold
     */
    static Outcome interpret(int status, String body, BigDecimal amount, String clientId) {
        if (status == 201) {
            return held(body, amount);
        }
        @Nullable JsonNode problem = parse(body);
        @Nullable String code = text(problem, "code");
        @Nullable String detail = text(problem, "detail");
        if ((status == 409 && "INSUFFICIENT_FUNDS".equals(code))
                || (status == 404 && "RESOURCE_NOT_FOUND".equals(code))
                || (status == 422 && "HOLD_TTL_TOO_LONG".equals(code))) {
            return new Refused(code, shorten(detail == null ? "" : detail));
        }
        String answer = "HTTP " + status + (code == null ? "" : " " + code)
                + (detail == null ? "" : " (" + shorten(detail) + ")");
        if (status == 401 || status == 403) {
            throw new IllegalStateException("The ledger refused " + CALL + " with " + answer + ". The token comes "
                    + "from client '" + clientId + "' and asks for " + SCOPE + ": the client must offer " + SCOPE
                    + " as an optional client scope, and the ledger must trust the issuer it came from.");
        }
        if (status == 429 || status >= 500) {
            throw new UpstreamUnavailableException(UPSTREAM,
                    new IllegalStateException(answer + " from the ledger on " + CALL));
        }
        throw new IllegalStateException("Unexpected " + answer + " from the ledger on " + CALL);
    }

    /** A {@code 201}'s body, checked field by field: a hold of the amount asked for, or an error. */
    private static Held held(String body, BigDecimal amount) {
        @Nullable JsonNode hold = parse(body);
        if (hold == null || !hold.isObject()) {
            throw unexpected("its body is not a JSON object");
        }
        UUID reservationId = uuid(text(hold, "reservation_id"), "reservation_id");
        BigDecimal held = decimal(text(hold, "amount"), "amount");
        if (held.compareTo(amount) != 0) {
            throw unexpected("it holds " + held.toPlainString() + ", not the " + amount.toPlainString() + " asked for");
        }
        @Nullable String state = text(hold, "state");
        if (state == null || !STATES.contains(state)) {
            throw unexpected("its state is " + shown(state) + ", which is none of the ledger's");
        }
        @Nullable String expiresAt = text(hold, "expires_at");
        Instant expires;
        try {
            expires = Instant.parse(expiresAt == null ? "" : expiresAt);
        } catch (DateTimeParseException notAnInstant) {
            throw unexpected("its expires_at is " + shown(expiresAt));
        }
        @Nullable JsonNode buckets = hold.get("allocations");
        if (buckets == null || !buckets.isArray() || buckets.isEmpty()) {
            throw unexpected("it names no allocations");
        }
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode bucket : buckets.values()) {
            @Nullable String type = text(bucket, "bucket_type");
            if (type == null || type.isBlank()) {
                throw unexpected("an allocation has no bucket_type");
            }
            BigDecimal part = decimal(text(bucket, "amount"), "allocations[].amount");
            if (part.signum() <= 0) {
                throw unexpected("an allocation of " + part.toPlainString() + " is not positive");
            }
            allocations.add(new Allocation(uuid(text(bucket, "bucket_id"), "allocations[].bucket_id"), type, part));
            total = total.add(part);
        }
        if (total.compareTo(amount) != 0) {
            throw unexpected("its allocations add up to " + total.toPlainString() + ", not " + amount.toPlainString());
        }
        return new Held(reservationId, state, expires, List.copyOf(allocations));
    }

    private static IllegalStateException unexpected(String what) {
        return new IllegalStateException("Unexpected HTTP 201 from the ledger on " + CALL + ": " + what
                + ". Is LEDGER_URL the ledger's address?");
    }

    private static UUID uuid(@Nullable String value, String field) {
        try {
            return UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException notAUuid) {
            throw unexpected("its " + field + " is " + shown(value));
        }
    }

    private static BigDecimal decimal(@Nullable String value, String field) {
        try {
            return new BigDecimal(value == null ? "" : value);
        } catch (NumberFormatException notADecimal) {
            throw unexpected("its " + field + " is " + shown(value));
        }
    }

    private static @Nullable JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException notJson) {
            return null;
        }
    }

    /** A string field, or null when it is missing or not a string. */
    private static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        @Nullable JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static String shorten(String text) {
        return text.length() <= DETAIL_LIMIT ? text : text.substring(0, DETAIL_LIMIT - 1) + "…";
    }

    /** Whatever a field held, short enough for a message, or that it was not there. */
    private static String shown(@Nullable String value) {
        if (value == null) {
            return "(none)";
        }
        return value.length() <= 64 ? "'" + value + "'" : "'" + value.substring(0, 63) + "…'";
    }

    /** The body, up to 64 KiB: a hold with its allocations, or a problem document. */
    private static String read(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(65_536), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static RestClient client(String url, Duration timeout) {
        // HTTP/1.1 explicitly, as wallet-auth-service does: no h2c upgrade attempt on every
        // connection to a plain-http in-cluster Service.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        // RestClient.builder(), not Boot's: nothing here needs a message converter beyond String, and
        // the ledger's wire format is written out above rather than left to a naming strategy.
        return RestClient.builder()
                .requestFactory(requests)
                .baseUrl(url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .build();
    }

    private static String required(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set: every debit is a hold the ledger places");
        }
        return value;
    }
}
