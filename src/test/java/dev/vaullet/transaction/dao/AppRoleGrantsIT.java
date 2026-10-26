package dev.vaullet.transaction.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.transaction.dao.TransactionRepository.AllocationRow;
import dev.vaullet.transaction.dao.TransactionRepository.NewTransactionRow;
import dev.vaullet.transaction.dao.TransactionRepository.TransactionRow;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2__transactions.sql's grants, tested as the role they grant to — the ledger's {@code AppRoleGrantsIT},
 * for this service.
 *
 * <p>Every other test connects as the database's owner, which may do anything, so none of them would
 * notice a missing grant, and a missing grant shows only in production, at the first request that
 * needs it. Here every statement {@link TransactionRepository} issues runs on a connection that is
 * {@code transaction_app}'s and nobody else's, and a {@code TransactionTemplate} on that connection
 * does what {@code TransactionService}'s does in the application.
 *
 * <p>The other half is what {@code transaction_app} must never do, refused by the database rather
 * than by the code: no DELETE or TRUNCATE, no UPDATE of an allocation, nothing at all on
 * {@code flyway_schema_history}, and no DDL.
 *
 * <p>V1 leaves the role NOLOGIN, with no password, as a migration must. Each test lends it a login
 * from the owner's connection and takes it back afterwards.
 */
@IntegrationTest
class AppRoleGrantsIT {

    /** SQLSTATE insufficient_privilege: the database said no, not the code. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Lives for one test, on a database the test suite owns. */
    private static final String TEST_ONLY_PASSWORD = "test-only-transaction-app";

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private JdbcTemplate owner;

    private JdbcTemplate app;
    private TransactionTemplate inOneTransaction;
    private TransactionRepository transactions;

    @BeforeEach
    void connectAsTransactionApp() {
        owner.execute("TRUNCATE transaction_allocations, transactions");
        owner.execute("ALTER ROLE transaction_app LOGIN PASSWORD '" + TEST_ONLY_PASSWORD + "'");

        DataSource asTransactionApp = new DriverManagerDataSource(databaseUrl(), "transaction_app", TEST_ONLY_PASSWORD);
        app = new JdbcTemplate(asTransactionApp);
        inOneTransaction = new TransactionTemplate(new DataSourceTransactionManager(asTransactionApp));
        transactions = new TransactionRepository(app, new TransactionStatements(), JsonMapper.shared());

        // Every assertion below means something only because this is not the owner's connection.
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("transaction_app");
    }

    @AfterEach
    void putEverythingBack() {
        owner.execute("ALTER ROLE transaction_app NOLOGIN PASSWORD NULL");
    }

    // ---------------------------------------------------------------- what the service does

    @Test
    @DisplayName("transaction_app records a debit, holds its lease, gives it back, authorizes it, and reads it all")
    void aDebitAsTransactionApp() {
        TransactionRow debit = transactions.insert(row("DEBIT", "PENDING", "key-1"), LEASE).orElseThrow();
        Instant lease = Objects.requireNonNull(debit.attemptUntil());
        // A retry while the lease runs finds it taken; the key's own row is there to find.
        assertThat(transactions.insert(row("DEBIT", "PENDING", "key-1"), LEASE)).isEmpty();
        assertThat(transactions.findByKey("requester", "key-1")).map(TransactionRow::transactionId)
                .contains(debit.transactionId());
        assertThat(transactions.claimAttempt(debit.transactionId(), LEASE)).isEmpty();

        transactions.endAttempt(debit.transactionId(), lease);
        Instant retried = transactions.claimAttempt(debit.transactionId(), LEASE).orElseThrow();

        UUID reservation = UUID.randomUUID();
        Boolean recorded = inOneTransaction.execute(status -> {
            boolean completed = transactions.complete(
                    debit.transactionId(), "AUTHORIZED", reservation, retried.plus(Duration.ofMinutes(5)));
            transactions.insertAllocations(debit.transactionId(), List.of(
                    new AllocationRow(UUID.randomUUID(), "BONUS", new BigDecimal("12.0000")),
                    new AllocationRow(UUID.randomUUID(), "CASH", new BigDecimal("8.0000"))));
            return completed;
        });
        assertThat(recorded).isTrue();

        TransactionRow authorized = transactions.findById(debit.transactionId()).orElseThrow();
        assertThat(authorized.status()).isEqualTo("AUTHORIZED");
        assertThat(authorized.reservationId()).isEqualTo(reservation);
        assertThat(authorized.attemptUntil()).isNull();
        assertThat(authorized.metadata()).containsEntry("market", "match-winner");
        assertThat(transactions.allocationsOf(debit.transactionId()))
                .extracting(AllocationRow::bucketType)
                .containsExactly("BONUS", "CASH");
    }

    @Test
    @DisplayName("transaction_app declines a debit and records a credit")
    void aDeclineAndACreditAsTransactionApp() {
        TransactionRow debit = transactions.insert(row("DEBIT", "PENDING", "key-2"), LEASE).orElseThrow();
        assertThat(transactions.decline(debit.transactionId(), "INSUFFICIENT_FUNDS", "available 5 < requested 20"))
                .isTrue();
        assertThat(transactions.findById(debit.transactionId()).orElseThrow().declineCode())
                .isEqualTo("INSUFFICIENT_FUNDS");

        TransactionRow credit = transactions.insert(row("CREDIT", "AUTHORIZED", "key-3"), null).orElseThrow();
        assertThat(credit.attemptUntil()).isNull();
        assertThat(credit.status()).isEqualTo("AUTHORIZED");
    }

    // ---------------------------------------------------------------- what it must never do

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"transactions", "transaction_allocations"})
    @DisplayName("nothing is erased: DELETE and TRUNCATE are refused")
    void nothingIsErased(String table) {
        assertRefused(() -> app.execute("DELETE FROM " + table));
        assertRefused(() -> app.execute("TRUNCATE " + table));
    }

    @Test
    @DisplayName("an allocation, written with the ledger's answer, stays as written: UPDATE is refused")
    void allocationsStayWritten() {
        assertRefused(() -> app.execute("UPDATE transaction_allocations SET amount = 1"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "SELECT count(*) FROM flyway_schema_history",
            "INSERT INTO flyway_schema_history DEFAULT VALUES",
            "UPDATE flyway_schema_history SET checksum = 0",
            "DELETE FROM flyway_schema_history"})
    @DisplayName("the migration history is out of reach, so validate-on-migrate can trust it")
    void migrationHistoryIsOutOfReach(String statement) {
        assertRefused(() -> app.execute(statement));
    }

    @Test
    @DisplayName("no DDL: transaction_app can neither create, alter nor drop a table, nor grant itself more")
    void noDdl() {
        assertRefused(() -> app.execute("CREATE TABLE scratch (id INT)"));
        assertRefused(() -> app.execute("ALTER TABLE transactions ADD COLUMN note TEXT"));
        assertRefused(() -> app.execute("DROP TABLE transaction_allocations"));

        // A role cannot hand itself what it was never given: PostgreSQL warns, and grants nothing.
        app.execute("GRANT DELETE ON transactions TO transaction_app");
        assertThat(app.queryForObject("SELECT has_table_privilege('transaction_app', 'transactions', 'DELETE')",
                Boolean.class)).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    private static NewTransactionRow row(String type, String status, String key) {
        return new NewTransactionRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                type,
                new BigDecimal("20.00"),
                "EUR",
                status,
                "bet-8891",
                Map.of("market", "match-winner"),
                type.equals("DEBIT") ? 300 : null,
                "requester",
                "operator-backend",
                key,
                "hash-" + key);
    }

    /** The database the suite runs on, wherever that is: the URL of the owner's own connection. */
    private String databaseUrl() {
        return Objects.requireNonNull(
                owner.execute((ConnectionCallback<@Nullable String>) connection -> connection.getMetaData().getURL()));
    }

    private static void assertRefused(ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        refusal -> assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }
}
