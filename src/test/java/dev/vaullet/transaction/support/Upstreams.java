package dev.vaullet.transaction.support;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * One {@link StubServer} standing in for both of this service's upstreams, Keycloak's token endpoint
 * and the ledger, with the application pointed at it: {@code @IntegrationTest @Import(Upstreams.class)}.
 *
 * <p>A bean of the context rather than a field of a test, because {@code LedgerReservations} reads its
 * addresses once, when the context starts. The stand-in starts with the context and closes with it,
 * and a {@link DynamicPropertyRegistrar} hands its address to the properties before anything binds
 * them. Every class that imports this shares one context, so the import must stay the same on all of
 * them.
 *
 * <p>Keycloak always issues a token here, a new one each time ({@code stub-token-1}, {@code -2}, …),
 * so a test is only ever about the ledger's answer: {@link #ledger(Function)} sets it.
 *
 * <p>The timeouts are one second, not five, so a test of a ledger that does not answer in time takes
 * one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class Upstreams {

    public static final String TOKEN_PATH = "/realms/vaullet/protocol/openid-connect/token";

    /** The stand-in, and what it is told to answer. */
    public static final class Stub {

        private final StubServer server;
        private final AtomicInteger tokensIssued = new AtomicInteger();
        private final AtomicReference<StubServer.Request> lastTokenRequest = new AtomicReference<>();

        Stub() {
            this.server = new StubServer(request -> StubServer.Response.problem(503, "UPSTREAM_UNAVAILABLE"));
            ledger(request -> StubServer.Response.problem(503, "ACCOUNT_BUSY"));
        }

        /** From now on the ledger answers as this says. Keycloak keeps issuing tokens. */
        public void ledger(Function<StubServer.Request, StubServer.Response> answer) {
            server.answer(request -> request.path().equals(TOKEN_PATH) ? token(request) : answer.apply(request));
        }

        /** Every request the ledger was sent, oldest first: not Keycloak's. */
        public List<StubServer.Request> ledgerRequests() {
            return server.requests().stream().filter(request -> !request.path().equals(TOKEN_PATH)).toList();
        }

        /** Every request Keycloak's token endpoint was sent. */
        public List<StubServer.Request> tokenRequests() {
            return server.requests().stream().filter(request -> request.path().equals(TOKEN_PATH)).toList();
        }

        public void forget() {
            server.forget();
        }

        String url() {
            return server.url();
        }

        void close() {
            server.close();
        }

        /**
         * The last request Keycloak's token endpoint was sent, kept across {@link #forget()}:
         * {@code LedgerReservations} caches its token for the life of the context, so the test that
         * looks at the request is rarely the one whose call made it.
         */
        public Optional<StubServer.Request> lastTokenRequest() {
            return Optional.ofNullable(lastTokenRequest.get());
        }

        private StubServer.Response token(StubServer.Request request) {
            lastTokenRequest.set(request);
            return StubServer.Response.json(200, """
                    {"access_token": "stub-token-%d", "token_type": "Bearer", "expires_in": 300}"""
                    .formatted(tokensIssued.incrementAndGet()));
        }
    }

    @Bean(destroyMethod = "close")
    Stub upstreamStub() {
        return new Stub();
    }

    @Bean
    DynamicPropertyRegistrar upstreamAddresses(Stub stub) {
        return registry -> {
            registry.add("transaction.keycloak.url", stub::url);
            registry.add("transaction.keycloak.client-secret", () -> "test-only-secret");
            registry.add("transaction.keycloak.timeout", () -> "1s");
            registry.add("transaction.ledger.url", stub::url);
            registry.add("transaction.ledger.timeout", () -> "1s");
        };
    }
}
