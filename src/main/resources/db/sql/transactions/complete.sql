-- The ledger holds the money: the debit takes the hold's state (AUTHORIZED for a hold that is
-- HELD) and records which hold it is and when it ends.
--
-- Only from PENDING. Two attempts that both reached the ledger got the same hold back, because the
-- ledger keys it on this transaction; whichever writes first records it, and the other updates
-- nothing and reads the row back.
UPDATE transactions
   SET status = ?, reservation_id = ?, expires_at = ?, attempt_until = NULL, updated_at = now()
 WHERE transaction_id = ?
   AND status = 'PENDING'
