-- Transactions (#1): every request to move money this service has been asked for, who asked, and
-- what the ledger answered.
--
-- The ledger owns the money (ADR-004). This table owns the request: a DEBIT asks the ledger for a
-- hold, a CREDIT is recorded for capture later, and either way the row is the answer to "who asked
-- for this, when, and what became of it". The rows are the record a caller's retry replays from,
-- which is why the idempotency key lives here rather than in a cache: a transaction IS the record of
-- its key, so a key never expires and never answers for a different request.
--
-- public, as in V1: transactions_db is this service's own database (ADR-003), so there is no
-- neighbour to fence off with a schema.

CREATE TABLE transactions (
    transaction_id   UUID          PRIMARY KEY,
    account_id       UUID          NOT NULL,
    type             TEXT          NOT NULL,
    -- NUMERIC(20,4), the ledger's own money type, so an amount it accepts is an amount this column
    -- can store exactly. The deployment currency's minor unit narrows it further, in the service.
    amount           NUMERIC(20,4) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    status           TEXT          NOT NULL,
    reference        TEXT,
    metadata         JSONB         NOT NULL DEFAULT '{}',
    -- What the caller asked for (expires_in_seconds), not what the ledger decided: NULL is the
    -- ledger's default. The ledger's answer is expires_at.
    hold_seconds     INTEGER,
    reservation_id   UUID,
    expires_at       TIMESTAMPTZ,
    -- The ledger's refusal, as its problem+json said it: the code a caller branches on, and the
    -- detail it was given. A replay answers with the same two.
    decline_code     TEXT,
    decline_detail   TEXT,
    -- Who asked: the token's sub, a person's Keycloak id or a service account's, and the client
    -- they asked through (azp). requested_by is also the idempotency key's scope, so one caller's
    -- key never collides with, or answers for, another's.
    requested_by     TEXT          NOT NULL,
    client_id        TEXT,
    idempotency_key  TEXT          NOT NULL,
    -- SHA-256 of the request as the service understood it (Fingerprint). The same key with a
    -- different request is IDEMPOTENCY_KEY_REUSED (ADR-012), never a replay of the first.
    request_hash     TEXT          NOT NULL,
    -- Until when a call to the ledger is in flight for this transaction. Only a PENDING debit has
    -- one; a retry that finds it in the future is REQUEST_IN_PROGRESS, one that finds it past or
    -- NULL takes it over. See claim-attempt.sql.
    attempt_until    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT transactions_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT transactions_amount_positive CHECK (amount > 0),
    -- The whole lifecycle ADR-012 describes, though #1 reaches only some of it: PENDING, AUTHORIZED
    -- and DECLINED, and VOIDED, EXPIRED or CAPTURED when a retry finds the ledger's hold already
    -- released, expired or settled. Capture and void come with their endpoints.
    CONSTRAINT transactions_status CHECK (
        status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'CAPTURED', 'VOIDED', 'EXPIRED')),
    CONSTRAINT transactions_reference_length CHECK (char_length(reference) BETWEEN 1 AND 255),
    CONSTRAINT transactions_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT transactions_hold_seconds_positive CHECK (hold_seconds > 0),
    CONSTRAINT transactions_key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT transactions_idempotency UNIQUE (requested_by, idempotency_key),
    -- One transaction per hold. The ledger keys the hold on this transaction's id, so a second
    -- transaction naming the same hold would be a bug somewhere, and this makes it a loud one.
    CONSTRAINT transactions_reservation UNIQUE (reservation_id),

    -- The rules below are the state machine's, written where no code path can skip them.
    --
    -- A credit is never held: nothing asked for, nothing placed. It is posted at capture.
    CONSTRAINT transactions_credit_holds_nothing CHECK (
        type = 'DEBIT' OR (hold_seconds IS NULL AND reservation_id IS NULL AND expires_at IS NULL)),
    -- PENDING is a debit waiting for the ledger. A credit has nobody to wait for.
    CONSTRAINT transactions_pending_is_a_debit CHECK (status <> 'PENDING' OR type = 'DEBIT'),
    -- A debit the ledger said yes to names its hold, and when the hold ends.
    CONSTRAINT transactions_debit_has_its_hold CHECK (
        type <> 'DEBIT' OR status IN ('PENDING', 'DECLINED')
        OR (reservation_id IS NOT NULL AND expires_at IS NOT NULL)),
    -- DECLINED, and only DECLINED, says why.
    CONSTRAINT transactions_declined_says_why CHECK ((status = 'DECLINED') = (decline_code IS NOT NULL)),
    -- Only a PENDING transaction can have a call to the ledger in flight.
    CONSTRAINT transactions_attempt_only_while_pending CHECK (attempt_until IS NULL OR status = 'PENDING')
);

-- Which buckets the ledger's hold draws on, and how much from each (ADR-012 §2 returns them). A
-- copy of the ledger's answer, kept so a replay and GET /v1/transactions/{id} need not ask again.
-- position keeps the ledger's order, which is its spending order: BONUS before CASH, say.
CREATE TABLE transaction_allocations (
    transaction_id  UUID          NOT NULL REFERENCES transactions (transaction_id),
    position        SMALLINT      NOT NULL,
    bucket_id       UUID          NOT NULL,
    bucket_type     TEXT          NOT NULL,
    amount          NUMERIC(20,4) NOT NULL,
    PRIMARY KEY (transaction_id, position),
    CONSTRAINT transaction_allocations_amount_positive CHECK (amount > 0)
);

-- What TransactionRepository issues as transaction_app, the role the running service holds, and
-- nothing more. AppRoleGrantsIT runs those statements as that role, so a statement that needs more
-- fails the build rather than the first request in production.
--
-- No DELETE and no TRUNCATE on either table. A transaction ends DECLINED, VOIDED, EXPIRED or
-- CAPTURED; it is never erased, and withholding the privilege makes that structural.
GRANT SELECT, INSERT, UPDATE ON transactions TO transaction_app;
-- Written once, with the ledger's answer, and read back. Never changed.
GRANT SELECT, INSERT ON transaction_allocations TO transaction_app;
