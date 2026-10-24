package dev.vaullet.transaction;

import dev.vaullet.common.test.PostgresContainerConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application from the IDE with its database supplied by Testcontainers: a throwaway
 * PostgreSQL, migrated by Flyway, with no {@code docker compose up} and nothing to clean up. It
 * uses the same container definition the integration tests do.
 *
 * <p>Running {@link TransactionApplication} directly fails: it gets no profile, and the security
 * chain refuses to start without {@code OAUTH2_ISSUER_URI}. That is the design, not a bug.
 */
public final class TestTransactionApplication {

    private TestTransactionApplication() {}

    public static void main(String[] args) {
        SpringApplication.from(TransactionApplication::main)
                .with(PostgresContainerConfiguration.class)
                .run(concat(args, "--spring.profiles.active=local"));
    }

    private static String[] concat(String[] args, String extra) {
        String[] all = new String[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = extra;
        return all;
    }
}
