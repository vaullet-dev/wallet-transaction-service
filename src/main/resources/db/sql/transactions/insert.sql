-- Record a request, or learn that this caller's key already recorded one.
--
-- ON CONFLICT DO NOTHING on the idempotency key: two requests racing with the same key are settled
-- by the unique constraint, and the loser gets no row back and reads the winner's
-- (find-by-key.sql). Nothing is decided by a read that comes before this insert.
--
-- attempt_until is set in the same statement for a debit, so the caller that inserted the row owns
-- the first call to the ledger from the moment the row exists: a retry arriving a millisecond later
-- finds it in flight rather than starting a second one. A credit passes NULL: nothing to call.
--
-- The cast on the lease is for the NULL a credit passes, which PostgreSQL cannot otherwise type.
INSERT INTO transactions (transaction_id, account_id, type, amount, currency, status, reference, metadata,
                          hold_seconds, requested_by, client_id, idempotency_key, request_hash, attempt_until)
VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, now() + make_interval(secs => ?::double precision))
ON CONFLICT (requested_by, idempotency_key) DO NOTHING
RETURNING transaction_id, account_id, type, amount, currency, status, reference, metadata::text AS metadata,
          hold_seconds, reservation_id, expires_at, decline_code, decline_detail, requested_by, client_id,
          idempotency_key, request_hash, attempt_until, created_at, updated_at
