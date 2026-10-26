package dev.vaullet.transaction.dao;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every statement against {@code transactions} and {@code transaction_allocations}. One line each:
 * the SQL, and why it is written the way it is, is in {@code db/sql/transactions/}.
 *
 * <p>No transaction boundaries here. Each method is one statement, and where two must commit
 * together — a hold's outcome and its allocations — {@code TransactionService} says so, because it
 * is the one that knows they belong together.
 */
@Repository
public class TransactionRepository {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {};

    /** A transaction as stored. Strings where the domain has enums: the service owns those. */
    public record TransactionRow(
            UUID transactionId,
            UUID accountId,
            String type,
            BigDecimal amount,
            String currency,
            String status,
            @Nullable String reference,
            Map<String, String> metadata,
            @Nullable Integer holdSeconds,
            @Nullable UUID reservationId,
            @Nullable Instant expiresAt,
            @Nullable String declineCode,
            @Nullable String declineDetail,
            String requestedBy,
            @Nullable String clientId,
            String idempotencyKey,
            String requestHash,
            @Nullable Instant attemptUntil,
            Instant createdAt,
            Instant updatedAt) {}

    /** What {@link #insert} records: a request, before anybody has answered it. */
    public record NewTransactionRow(
            UUID transactionId,
            UUID accountId,
            String type,
            BigDecimal amount,
            String currency,
            String status,
            @Nullable String reference,
            Map<String, String> metadata,
            @Nullable Integer holdSeconds,
            String requestedBy,
            @Nullable String clientId,
            String idempotencyKey,
            String requestHash) {}

    /** One bucket's part of a hold, in the ledger's order. */
    public record AllocationRow(UUID bucketId, String bucketType, BigDecimal amount) {}

    private final JdbcTemplate jdbc;
    private final TransactionStatements sql;
    private final JsonMapper json;
    private final RowMapper<TransactionRow> rows;

    TransactionRepository(JdbcTemplate jdbc, TransactionStatements sql, JsonMapper json) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.json = json;
        this.rows = this::row;
    }

    /**
     * Record a request, unless this caller's key already recorded one.
     *
     * @param lease for a debit, how long its first call to the ledger may take before a retry can
     *     take it over; null for a credit, which calls nobody
     * @return the row as stored, or empty when the key was taken: {@link #findByKey} has the
     *     transaction it recorded
     */
    public Optional<TransactionRow> insert(NewTransactionRow row, @Nullable Duration lease) {
        return jdbc.query(sql.insert, rows,
                        row.transactionId(),
                        row.accountId(),
                        row.type(),
                        row.amount(),
                        row.currency(),
                        row.status(),
                        row.reference(),
                        json.writeValueAsString(row.metadata()),
                        row.holdSeconds(),
                        row.requestedBy(),
                        row.clientId(),
                        row.idempotencyKey(),
                        row.requestHash(),
                        lease == null ? null : seconds(lease))
                .stream()
                .findFirst();
    }

    public Optional<TransactionRow> findByKey(String requestedBy, String idempotencyKey) {
        return jdbc.query(sql.findByKey, rows, requestedBy, idempotencyKey).stream().findFirst();
    }

    public Optional<TransactionRow> findById(UUID transactionId) {
        return jdbc.query(sql.findById, rows, transactionId).stream().findFirst();
    }

    /**
     * Take over the call to the ledger for a PENDING debit, unless one is in flight.
     *
     * @return the new deadline when this caller has the attempt; empty when another caller has it,
     *     or the transaction is no longer PENDING
     */
    public Optional<Instant> claimAttempt(UUID transactionId, Duration lease) {
        // RETURNING the deadline the UPDATE just set, which is never NULL.
        return jdbc.query(sql.claimAttempt,
                        (rs, i) -> rs.getObject("attempt_until", OffsetDateTime.class).toInstant(),
                        seconds(lease),
                        transactionId)
                .stream()
                .findFirst();
    }

    /** Give the lease back, if it is still this attempt's: {@code attemptUntil} is the one it was given. */
    public void endAttempt(UUID transactionId, Instant attemptUntil) {
        jdbc.update(sql.endAttempt, transactionId, utc(attemptUntil));
    }

    /**
     * Record the ledger's hold on a PENDING debit.
     *
     * @return false when the transaction was no longer PENDING: another attempt recorded it first
     */
    public boolean complete(UUID transactionId, String status, UUID reservationId, Instant expiresAt) {
        return jdbc.update(sql.complete, status, reservationId, utc(expiresAt), transactionId) == 1;
    }

    /**
     * Record the ledger's refusal of a PENDING debit.
     *
     * @return false when the transaction was no longer PENDING: another attempt recorded an answer
     *     first
     */
    public boolean decline(UUID transactionId, String code, String detail) {
        return jdbc.update(sql.decline, code, detail, transactionId) == 1;
    }

    public void insertAllocations(UUID transactionId, List<AllocationRow> allocations) {
        for (int position = 0; position < allocations.size(); position++) {
            AllocationRow allocation = allocations.get(position);
            jdbc.update(sql.insertAllocation, transactionId, position, allocation.bucketId(),
                    allocation.bucketType(), allocation.amount());
        }
    }

    public List<AllocationRow> allocationsOf(UUID transactionId) {
        return jdbc.query(sql.allocationsOf,
                (rs, i) -> new AllocationRow(
                        rs.getObject("bucket_id", UUID.class), rs.getString("bucket_type"), rs.getBigDecimal("amount")),
                transactionId);
    }

    private TransactionRow row(ResultSet rs, int rowNum) throws SQLException {
        Instant expiresAt = instant(rs, "expires_at");
        Instant attemptUntil = instant(rs, "attempt_until");
        Instant createdAt = instant(rs, "created_at");
        Instant updatedAt = instant(rs, "updated_at");
        if (createdAt == null || updatedAt == null) {
            throw new SQLException("transactions.created_at and updated_at are NOT NULL, yet one came back null");
        }
        return new TransactionRow(
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("type"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("reference"),
                json.readValue(rs.getString("metadata"), METADATA),
                rs.getObject("hold_seconds", Integer.class),
                rs.getObject("reservation_id", UUID.class),
                expiresAt,
                rs.getString("decline_code"),
                rs.getString("decline_detail"),
                rs.getString("requested_by"),
                rs.getString("client_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                attemptUntil,
                createdAt,
                updatedAt);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** pgjdbc binds OffsetDateTime to timestamptz; it has no binding for Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** make_interval(secs => ...) takes fractional seconds, so a lease keeps its milliseconds. */
    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
