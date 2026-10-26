-- The ledger said no. The transaction stays, DECLINED, with the ledger's code and detail: a replay
-- of the same request answers with the same refusal, and nobody asks the ledger twice.
UPDATE transactions
   SET status = 'DECLINED', decline_code = ?, decline_detail = ?, attempt_until = NULL, updated_at = now()
 WHERE transaction_id = ?
   AND status = 'PENDING'
