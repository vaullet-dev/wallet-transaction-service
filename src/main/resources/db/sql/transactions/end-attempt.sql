-- An attempt that ended without an answer from the ledger gives its lease back, so the caller's
-- retry can start at once instead of waiting for the lease to run out.
--
-- Only its own lease: the deadline it was given must still be the one on the row. One that has
-- run out and been taken over belongs to the attempt that took it.
UPDATE transactions
   SET attempt_until = NULL
 WHERE transaction_id = ?
   AND status = 'PENDING'
   AND attempt_until = ?
