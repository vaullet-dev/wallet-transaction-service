-- The transaction a caller's idempotency key recorded. The key's scope is the caller
-- (requested_by), so another caller's identical key is another transaction.
SELECT transaction_id, account_id, type, amount, currency, status, reference, metadata::text AS metadata,
       hold_seconds, reservation_id, expires_at, decline_code, decline_detail, requested_by, client_id,
       idempotency_key, request_hash, attempt_until, created_at, updated_at
  FROM transactions
 WHERE requested_by = ? AND idempotency_key = ?
