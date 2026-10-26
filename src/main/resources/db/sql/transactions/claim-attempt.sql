-- Take over the call to the ledger for a PENDING debit, unless one is in flight.
--
-- One statement, so two retries racing for the same transaction cannot both win: the row lock the
-- UPDATE takes makes the second re-check the WHERE after the first commits, and by then
-- attempt_until is in the future. The winner gets the new deadline back and calls the ledger; the
-- loser gets no row and answers REQUEST_IN_PROGRESS.
--
-- A deadline in the past means the attempt that set it is over, whatever became of it: its caller
-- saw a 503, or crashed. The lease outlasts the longest call to the ledger the timeouts allow
-- (TransactionService), so an attempt still running never has its lease taken over.
UPDATE transactions
   SET attempt_until = now() + make_interval(secs => ?::double precision)
 WHERE transaction_id = ?
   AND status = 'PENDING'
   AND (attempt_until IS NULL OR attempt_until < now())
RETURNING attempt_until
