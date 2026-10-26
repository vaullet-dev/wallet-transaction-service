package dev.vaullet.transaction.api.v1.dto;

import dev.vaullet.transaction.service.Allocation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * How much of a debit's hold one bucket funds. Returned rather than summed away (ADR-012 §2): an
 * operator shows a user that 12 of a 20 stake came from bonus funds, and wagering, withdrawability
 * and a void's refund all depend on which bucket it was.
 */
@Schema(name = "Allocation", description = "One bucket's part of a debit's hold")
public record AllocationResponse(
        UUID bucketId,
        @Schema(example = "BONUS") String bucketType,
        @Schema(type = "string", example = "12.00", description = "Decimal string, never a JSON number") String amount) {

    static AllocationResponse from(Allocation allocation) {
        return new AllocationResponse(
                allocation.bucketId(), allocation.bucketType(), allocation.amount().toPlainString());
    }
}
