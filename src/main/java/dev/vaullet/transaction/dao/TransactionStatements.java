package dev.vaullet.transaction.dao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * The SQL this service issues, read from {@code db/sql/transactions/} at startup — wallet-auth-service's
 * {@code AccountStatements}, for the same reasons: a {@code .sql} file is highlighted, formatted and
 * checkable as SQL, the reasoning behind each statement travels in the file with it, and a
 * repository whose methods read as one line each shows its shape at a glance.
 *
 * <p>Eager: every file is read in the constructor, so a renamed or missing one fails the context at
 * startup with the path in the message, not the first request that needs it.
 */
@Component
class TransactionStatements {

    private static final String BASE = "db/sql/transactions/";

    final String insert = read("insert.sql");
    final String findByKey = read("find-by-key.sql");
    final String findById = read("find-by-id.sql");
    final String claimAttempt = read("claim-attempt.sql");
    final String endAttempt = read("end-attempt.sql");
    final String complete = read("complete.sql");
    final String decline = read("decline.sql");
    final String insertAllocation = read("insert-allocation.sql");
    final String allocationsOf = read("allocations-of.sql");

    private static String read(String file) {
        var resource = new ClassPathResource(BASE + file);
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read SQL statement " + BASE + file, e);
        }
    }
}
