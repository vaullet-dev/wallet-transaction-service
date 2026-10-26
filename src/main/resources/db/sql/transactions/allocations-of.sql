-- In the ledger's order, which is the order it spends buckets in.
SELECT bucket_id, bucket_type, amount
  FROM transaction_allocations
 WHERE transaction_id = ?
 ORDER BY position
