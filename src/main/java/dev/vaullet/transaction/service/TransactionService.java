package dev.vaullet.transaction.service;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.ErrorType;
import dev.vaullet.common.error.exception.ResourceNotFoundException;
import dev.vaullet.common.error.exception.UpstreamUnavailableException;
import dev.vaullet.transaction.common.error.TransactionErrorType;
import dev.vaullet.transaction.common.error.exception.CurrencyMismatchException;
import dev.vaullet.transaction.common.error.exception.DebitPendingException;
import dev.vaullet.transaction.common.error.exception.IdempotencyKeyReusedException;
import dev.vaullet.transaction.common.error.exception.InvalidTransactionException;
import dev.vaullet.transaction.common.error.exception.RequestInProgressException;
import dev.vaullet.transaction.common.error.exception.TransactionDeclinedException;
import dev.vaullet.transaction.config.TransactionProperties;
import dev.vaullet.transaction.dao.LedgerReservations;
import dev.vaullet.transaction.dao.TransactionRepository;
import dev.vaullet.transaction.dao.TransactionRepository.AllocationRow;
import dev.vaullet.transaction.dao.TransactionRepository.NewTransactionRow;
import dev.vaullet.transaction.dao.TransactionRepository.TransactionRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recording a transaction, and for a debit, asking the ledger to hold the money (ADR-005: this
 * service is the orchestrator in front of the ledger; ADR-012 §2: the two-phase model, exposed).
 *
 * <p><b>The order is the design.</b> A debit is recorded PENDING <em>before</em> the ledger is asked,
 * so there is never a hold in the ledger that no transaction knows about. The ledger is then asked
 * with an idempotency key of its own, derived from the transaction's id, so asking twice — after a
 * timeout, or by two retries at once — holds the money once. And the answer is recorded only from
 * PENDING, so whichever attempt writes first decides, and every later one reads what it wrote.
 *
 * <p><b>No database transaction spans the call to the ledger.</b> It would hold a connection and a
 * row lock for as long as the ledger takes, and a slow ledger would drain the pool. Instead a PENDING
 * debit carries a lease, {@code attempt_until}: the caller holding it is the one calling the ledger,
 * and a retry that finds it running answers {@code REQUEST_IN_PROGRESS} rather than calling too. An
 * attempt that ends without an answer gives the lease back, so the caller's retry starts at once; one
 * that dies with its process leaves it to run out.
 *
 * <p><b>A caller's retry is the recovery path.</b> {@code Idempotency-Key} is required, and the same
 * key with the same request answers with the transaction as it stands: its outcome if it has one, or
 * a new attempt at the ledger if it is still PENDING. The same key with a different request is
 * {@code IDEMPOTENCY_KEY_REUSED}, never the first request's outcome (ADR-012, "Idempotency
 * semantics"). A PENDING debit nobody retries keeps whatever hold the ledger may have placed until
 * that hold expires; a sweeper that settles such debits is still to come.
 *
 * <p>Authorisation is here, on the methods, not on the controller: the rules are
 * {@link TransactionAccess}'s, and they hold for whatever calls these methods next.
 */
@Service
@Profile("!migrate")
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /**
     * Added to the longest call to the ledger the timeouts allow, for the statements on either side
     * of it. A lease that ran out while its attempt was still waiting for the ledger would let a
     * second attempt start, which is exactly what the lease is for preventing.
     */
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(10);

    private final TransactionRepository transactions;
    private final LedgerReservations ledger;
    private final TransactionTemplate inOneTransaction;
    private final String currency;
    private final int minorUnits;
    private final Duration lease;

    public TransactionService(
            TransactionRepository transactions,
            LedgerReservations ledger,
            TransactionTemplate inOneTransaction,
            TransactionProperties properties) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.inOneTransaction = inOneTransaction;
        Currency deployment = deploymentCurrency(properties.currency());
        this.currency = deployment.getCurrencyCode();
        this.minorUnits = minorUnits(properties.minorUnits(), deployment);
        this.lease = ledger.longestReserve().plus(LEASE_MARGIN);
    }

    /**
     * Record a transaction for an operator's backend: a debit or a credit, on any account.
     *
     * @param idempotencyKey the caller's key for this request, scoped to the caller
     * @return the transaction: a debit AUTHORIZED with its hold, a credit AUTHORIZED, or on a retry
     *     the transaction the key recorded, as it stands
     * @throws TransactionDeclinedException the ledger refused the debit, now or the first time
     * @throws IdempotencyKeyReusedException the key recorded a different request
     * @throws RequestInProgressException the key's request is being answered by another call
     * @throws DebitPendingException the ledger or Keycloak did not answer; the debit stays PENDING and
     *     a retry with the same key takes it up
     */
    @PreAuthorize(TransactionAccess.WRITE)
    public Transaction create(NewTransaction request, Requester requester, String idempotencyKey) {
        return submit(request, requester, idempotencyKey);
    }

    /**
     * Record an end user's debit on their own account. Everything {@link #create} does, for one
     * account and one type: {@link TransactionAccess#SELF} holds the request to the caller's account
     * and to {@code DEBIT}.
     */
    @PreAuthorize(TransactionAccess.SELF)
    public Transaction createOwn(NewTransaction request, Requester requester, String idempotencyKey) {
        return submit(request, requester, idempotencyKey);
    }

    /** Any transaction, for an operator's backend. */
    @Transactional(readOnly = true)
    @PreAuthorize(TransactionAccess.READ)
    public Transaction find(UUID transactionId) {
        return transactions.findById(transactionId).map(this::toTransaction).orElseThrow(() -> notFound(transactionId));
    }

    /**
     * A transaction on the caller's own account. Another account's is not found, exactly as an id that
     * does not exist: the answer must not tell the two apart.
     */
    @Transactional(readOnly = true)
    @PreAuthorize(TransactionAccess.SELF_READ)
    public Transaction findOwn(UUID transactionId, UUID accountId) {
        return transactions.findById(transactionId)
                .filter(row -> row.accountId().equals(accountId))
                .map(this::toTransaction)
                .orElseThrow(() -> notFound(transactionId));
    }

    private Transaction submit(NewTransaction request, Requester requester, String idempotencyKey) {
        check(request);
        String fingerprint = Fingerprint.of(request);
        boolean debit = request.type() == TransactionType.DEBIT;
        NewTransactionRow row = new NewTransactionRow(
                UUID.randomUUID(),
                request.accountId(),
                request.type().name(),
                request.amount(),
                request.currency(),
                // A credit has nobody to wait for: it is AUTHORIZED as it is recorded, and posted at
                // capture. A debit waits for the ledger.
                (debit ? TransactionStatus.PENDING : TransactionStatus.AUTHORIZED).name(),
                request.reference(),
                request.metadata(),
                request.expiresInSeconds(),
                requester.subject(),
                requester.clientId(),
                idempotencyKey,
                fingerprint);
        Optional<TransactionRow> recorded = transactions.insert(row, debit ? lease : null);
        if (recorded.isPresent()) {
            TransactionRow created = recorded.get();
            if (!debit) {
                log.debug("Credit {} of {} {} recorded on account {}", created.transactionId(),
                        created.amount().toPlainString(), currency, created.accountId());
                return toTransaction(created);
            }
            return hold(created, Objects.requireNonNull(created.attemptUntil(), "a new debit has its lease"));
        }
        // The key recorded a transaction already: a retry, or a key used for two things.
        TransactionRow earlier = transactions.findByKey(requester.subject(), idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "The Idempotency-Key conflicted on insert, yet no transaction holds it"));
        if (!earlier.requestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(earlier.transactionId());
        }
        return replay(earlier);
    }

    /**
     * The rules a request is checked against before anything is recorded: they depend on the
     * deployment, so the request's own validation cannot know them.
     */
    private void check(NewTransaction request) {
        if (!currency.equals(request.currency())) {
            throw new CurrencyMismatchException(request.currency(), currency);
        }
        if (request.amount().stripTrailingZeros().scale() > minorUnits) {
            throw new InvalidTransactionException("amount: %s has %d decimal places, so %s is not an amount of it"
                    .formatted(currency, minorUnits, request.amount().toPlainString()));
        }
        if (request.type() == TransactionType.CREDIT && request.expiresInSeconds() != null) {
            throw new InvalidTransactionException(
                    "expires_in_seconds: a CREDIT holds nothing, so there is no hold to expire");
        }
    }

    /** A transaction this caller's key recorded earlier, answered as it stands. */
    private Transaction replay(TransactionRow row) {
        TransactionStatus status = TransactionStatus.valueOf(row.status());
        if (status == TransactionStatus.DECLINED) {
            throw declined(row);
        }
        if (status != TransactionStatus.PENDING) {
            return toTransaction(row);
        }
        // The ledger has not answered, as far as this row knows: the attempt that recorded it failed,
        // or is still waiting. Take it over if it is over.
        Optional<Instant> attempt = transactions.claimAttempt(row.transactionId(), lease);
        if (attempt.isPresent()) {
            return hold(row, attempt.get());
        }
        TransactionRow now = current(row.transactionId());
        if (TransactionStatus.valueOf(now.status()) == TransactionStatus.PENDING) {
            throw new RequestInProgressException(row.transactionId());
        }
        // Answered between the read and the claim.
        return replay(now);
    }

    /** Ask the ledger to hold a PENDING debit's money, as the holder of its lease, and record the answer. */
    private Transaction hold(TransactionRow row, Instant attemptUntil) {
        UUID transactionId = row.transactionId();
        LedgerReservations.Outcome outcome;
        try {
            // In the currency's minor units, 20.00 rather than the column's 20.0000: the amount as the
            // caller asked for it, which is what the ledger's own record should say too.
            outcome = ledger.reserve(
                    row.accountId(), inMinorUnits(row.amount()), row.holdSeconds(), ledgerKey(transactionId));
        } catch (UpstreamUnavailableException notNow) {
            // The ledger may or may not hold the money: a timeout can come after its commit. The debit
            // stays PENDING either way, and a retry asks again with the same ledger key, which answers
            // with the hold if there is one. Giving the lease back lets that retry start now.
            log.warn("Debit {} stays PENDING: {}", transactionId, notNow.getMessage());
            endAttempt(transactionId, attemptUntil, notNow);
            throw new DebitPendingException(transactionId, notNow);
        } catch (RuntimeException failed) {
            // An answer nobody expected, or a refusal of this service: a person has to look. The debit
            // stays PENDING, and the lease goes back so that a retry finds the same failure at once,
            // rather than REQUEST_IN_PROGRESS until the lease runs out.
            endAttempt(transactionId, attemptUntil, failed);
            throw failed;
        }
        try {
            return switch (outcome) {
                case LedgerReservations.Held held -> authorized(row, held);
                case LedgerReservations.Refused refused -> refused(row, refused);
            };
        } catch (RuntimeException notRecorded) {
            // The ledger answered and recording it failed, a statement timeout say. Its hold, if any,
            // is the one the same key gets back, so the retry this lease is given back for records it.
            // After a refusal or an outcome that was recorded, the debit is no longer PENDING and
            // nothing is given back.
            endAttempt(transactionId, attemptUntil, notRecorded);
            throw notRecorded;
        }
    }

    /** Give a lease back, keeping the failure that ended the attempt as the one reported. */
    private void endAttempt(UUID transactionId, Instant attemptUntil, RuntimeException failure) {
        try {
            transactions.endAttempt(transactionId, attemptUntil);
        } catch (RuntimeException alsoFailed) {
            failure.addSuppressed(alsoFailed);
        }
    }

    private Transaction authorized(TransactionRow row, LedgerReservations.Held held) {
        UUID transactionId = row.transactionId();
        TransactionStatus status = switch (held.state()) {
            case "HELD" -> TransactionStatus.AUTHORIZED;
            case "SETTLED" -> TransactionStatus.CAPTURED;
            case "RELEASED" -> TransactionStatus.VOIDED;
            case "EXPIRED" -> TransactionStatus.EXPIRED;
            default -> throw new IllegalStateException("The ledger's hold is " + held.state()
                    + ", which LedgerReservations does not let through");
        };
        List<AllocationRow> allocations = held.allocations().stream()
                .map(allocation -> new AllocationRow(allocation.bucketId(), allocation.bucketType(), allocation.amount()))
                .toList();
        // The outcome and its allocations together, or neither: a transaction that says AUTHORIZED
        // with no buckets behind it would answer every later read wrongly.
        boolean recorded = Boolean.TRUE.equals(inOneTransaction.execute(state -> {
            if (!transactions.complete(transactionId, status.name(), held.reservationId(), held.expiresAt())) {
                return false;
            }
            transactions.insertAllocations(transactionId, allocations);
            return true;
        }));
        TransactionRow now = current(transactionId);
        if (recorded) {
            log.debug("Debit {} of {} {} on account {} is {}: hold {} until {}", transactionId,
                    row.amount().toPlainString(), currency, row.accountId(), status, held.reservationId(),
                    held.expiresAt());
        } else if (TransactionStatus.valueOf(now.status()) == TransactionStatus.DECLINED) {
            // Only reachable if an attempt outlived its lease, which the lease is sized to prevent:
            // another attempt was refused, this one was not, and the ledger now holds money for a
            // transaction that says it was declined. Nothing here can undo that safely, so it is said
            // loudly, with what a person needs to release the hold.
            log.error("The ledger holds {} {} for debit {} under reservation {} until {}, but the debit was "
                            + "recorded DECLINED ({}) by another attempt. Release the hold "
                            + "(DELETE /v1/reservations/{}) or let it expire.",
                    row.amount().toPlainString(), currency, transactionId, held.reservationId(), held.expiresAt(),
                    now.declineCode(), held.reservationId());
        }
        return replay(now);
    }

    private Transaction refused(TransactionRow row, LedgerReservations.Refused refused) {
        log.debug("Debit {} declined by the ledger: {} {}", row.transactionId(), refused.code(), refused.detail());
        transactions.decline(row.transactionId(), refused.code(), refused.detail());
        // DECLINED now, by this attempt or one before it, so this throws; or AUTHORIZED by an
        // attempt that got a yes first, which is then the answer.
        return replay(current(row.transactionId()));
    }

    private Transaction toTransaction(TransactionRow row) {
        List<Allocation> allocations = row.reservationId() == null
                ? List.of()
                : transactions.allocationsOf(row.transactionId()).stream()
                        .map(allocation -> new Allocation(
                                allocation.bucketId(), allocation.bucketType(), inMinorUnits(allocation.amount())))
                        .toList();
        return new Transaction(
                row.transactionId(),
                row.accountId(),
                TransactionType.valueOf(row.type()),
                TransactionStatus.valueOf(row.status()),
                inMinorUnits(row.amount()),
                row.currency(),
                row.reference(),
                row.metadata(),
                row.reservationId(),
                row.expiresAt(),
                allocations,
                row.declineCode(),
                row.createdAt(),
                row.updatedAt());
    }

    /**
     * An amount as the currency writes it: {@code 20.00} for EUR, whatever scale the column gave it
     * ({@code 20.0000}). Never rounded: an amount with more places than the minor unit keeps them.
     */
    private BigDecimal inMinorUnits(BigDecimal amount) {
        return amount.setScale(Math.max(minorUnits, amount.stripTrailingZeros().scale()));
    }

    private TransactionRow current(UUID transactionId) {
        return transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction " + transactionId + " is not there to read back"));
    }

    /**
     * The refusal a DECLINED transaction was given, as the error it was given as: the ledger's code
     * and detail, the first time and on every replay.
     */
    private static TransactionDeclinedException declined(TransactionRow row) {
        @Nullable String code = row.declineCode();
        ErrorType type = switch (code == null ? "" : code) {
            case "INSUFFICIENT_FUNDS" -> TransactionErrorType.INSUFFICIENT_FUNDS;
            case "HOLD_TTL_TOO_LONG" -> TransactionErrorType.HOLD_TTL_TOO_LONG;
            case "RESOURCE_NOT_FOUND" -> CommonErrorType.RESOURCE_NOT_FOUND;
            default -> throw new IllegalStateException(
                    "Transaction " + row.transactionId() + " is DECLINED with code " + code + ", which is none of the ledger's");
        };
        @Nullable String detail = row.declineDetail();
        return new TransactionDeclinedException(type, row.transactionId(),
                detail == null || detail.isBlank() ? "The ledger declined the transaction" : detail);
    }

    /**
     * The ledger's idempotency key for this transaction's hold. One per transaction, so every attempt
     * at it gets the same hold back; prefixed, so a hold in the ledger says where it came from.
     */
    private static String ledgerKey(UUID transactionId) {
        return "transaction:" + transactionId;
    }

    private static ResourceNotFoundException notFound(UUID transactionId) {
        return new ResourceNotFoundException("Transaction", transactionId);
    }

    private static Currency deploymentCurrency(@Nullable String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("transaction.currency (TRANSACTION_CURRENCY) must be set: the "
                    + "deployment's currency, ISO 4217, the same as the ledger's LEDGER_CURRENCY");
        }
        try {
            return Currency.getInstance(code.strip());
        } catch (IllegalArgumentException notIso) {
            throw new IllegalStateException("transaction.currency is '" + code + "', which is not an ISO 4217 code");
        }
    }

    private static int minorUnits(@Nullable Integer configured, Currency currency) {
        if (configured != null) {
            if (configured < 0 || configured > 4) {
                throw new IllegalStateException("transaction.minor-units is " + configured
                        + "; it must be 0 to 4, because amounts are NUMERIC(20,4)");
            }
            return configured;
        }
        int iso = currency.getDefaultFractionDigits();
        if (iso < 0) {
            throw new IllegalStateException(currency.getCurrencyCode()
                    + " has no minor unit in ISO 4217; set transaction.minor-units (TRANSACTION_MINOR_UNITS)");
        }
        return iso;
    }
}
