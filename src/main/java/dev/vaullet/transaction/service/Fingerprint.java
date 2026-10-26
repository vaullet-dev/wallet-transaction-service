package dev.vaullet.transaction.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What a request is, as one hash: the thing a repeated {@code Idempotency-Key} is compared on
 * (ADR-012, "Idempotency semantics"). The same key with the same fingerprint is a retry and gets the
 * first request's outcome; with another, it is {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Taken over the request as the service understood it, not over the bytes the caller sent. Two
 * retries of one request can be serialised differently — keys in another order, {@code "20.0"} for
 * {@code "20.00"}, the end user's own {@code account_id} written out or left to their token — and are
 * still one request. So: the amount without trailing zeros, the metadata in key order, the account
 * as resolved.
 *
 * <p>Each field is written with its length in front, so no value can be read as part of the next:
 * {@code ("ab", "c")} and {@code ("a", "bc")} are different requests and hash differently. The
 * leading {@code v1} names this encoding; changing what is hashed means a {@code v2}, so a stored
 * hash is never compared with one built another way.
 */
final class Fingerprint {

    private Fingerprint() {}

    static String of(NewTransaction request) {
        StringBuilder canonical = new StringBuilder("v1;");
        field(canonical, request.type().name());
        field(canonical, request.accountId().toString());
        field(canonical, request.amount().stripTrailingZeros().toPlainString());
        field(canonical, request.currency());
        field(canonical, request.expiresInSeconds() == null ? null : request.expiresInSeconds().toString());
        field(canonical, request.reference());
        Map<String, String> metadata = new TreeMap<>(request.metadata());
        canonical.append(metadata.size()).append(';');
        metadata.forEach((key, value) -> {
            field(canonical, key);
            field(canonical, value);
        });
        return HexFormat.of().formatHex(sha256(canonical.toString()));
    }

    private static void field(StringBuilder out, @Nullable String value) {
        if (value == null) {
            out.append("-;");
        } else {
            out.append(value.length()).append(':').append(value).append(';');
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }
}
