package dev.vaullet.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One request, one fingerprint; two requests, two. The first half is what makes a retry a replay,
 * the second what makes a reused key {@code IDEMPOTENCY_KEY_REUSED} rather than the wrong answer.
 */
class FingerprintTest {

    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

    private static NewTransaction debit(String amount, @Nullable String reference, Map<String, String> metadata) {
        return new NewTransaction(ACCOUNT, TransactionType.DEBIT, new BigDecimal(amount), "EUR", 300, reference, metadata);
    }

    @Test
    @DisplayName("the same request, serialised differently, is the same request")
    void same() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(Fingerprint.of(debit("20.00", "bet-1", ab))).isEqualTo(Fingerprint.of(debit("20", "bet-1", ba)));
        assertThat(Fingerprint.of(debit("20.10", null, Map.of()))).isEqualTo(Fingerprint.of(debit("20.1", null, Map.of())));
    }

    @Test
    @DisplayName("any field that differs makes another request")
    void different() {
        String base = Fingerprint.of(debit("20.00", "bet-1", Map.of("market", "winner")));

        assertThat(Fingerprint.of(debit("20.01", "bet-1", Map.of("market", "winner")))).isNotEqualTo(base);
        assertThat(Fingerprint.of(debit("20.00", "bet-2", Map.of("market", "winner")))).isNotEqualTo(base);
        assertThat(Fingerprint.of(debit("20.00", "bet-1", Map.of("market", "loser")))).isNotEqualTo(base);
        assertThat(Fingerprint.of(debit("20.00", "bet-1", Map.of()))).isNotEqualTo(base);
        assertThat(Fingerprint.of(new NewTransaction(
                        ACCOUNT, TransactionType.CREDIT, new BigDecimal("20.00"), "EUR", 300, "bet-1", Map.of("market", "winner"))))
                .isNotEqualTo(base);
        assertThat(Fingerprint.of(new NewTransaction(
                        ACCOUNT, TransactionType.DEBIT, new BigDecimal("20.00"), "EUR", null, "bet-1", Map.of("market", "winner"))))
                .isNotEqualTo(base);
        assertThat(Fingerprint.of(new NewTransaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("20.00"),
                        "EUR", 300, "bet-1", Map.of("market", "winner"))))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("no value can be read as part of the next: lengths go in front")
    void unambiguous() {
        assertThat(Fingerprint.of(debit("20", null, Map.of("ab", "c"))))
                .isNotEqualTo(Fingerprint.of(debit("20", null, Map.of("a", "bc"))));
        assertThat(Fingerprint.of(debit("20", null, Map.of()))).isNotEqualTo(Fingerprint.of(debit("20", "-", Map.of())));
    }
}
