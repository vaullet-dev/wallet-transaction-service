package dev.vaullet.transaction.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much of a debit's hold one bucket funds (ADR-012 §2). An 80 stake funded by 60 of bonus and 20
 * of cash spends, wagers and returns differently from 80 of cash, so the split is part of the
 * answer, not a detail of the ledger.
 */
public record Allocation(UUID bucketId, String bucketType, BigDecimal amount) {}
