package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result of audit trail chain integrity verification.
 *
 * @since 6.1.0
 */
@Data
@Builder
public class AuditChainVerificationResult {

    /** Whether the chain is intact */
    private boolean valid;

    /** Total records verified */
    private long recordsVerified;

    /** Sequence number of the first broken link (null if valid) */
    private Long brokenAtSequence;

    /** Expected hash at the broken link */
    private String expectedHash;

    /** Actual hash found at the broken link */
    private String actualHash;

    /** Human-readable message */
    private String message;

    public static AuditChainVerificationResult ok(long count) {
        return AuditChainVerificationResult.builder()
                .valid(true)
                .recordsVerified(count)
                .message("Chain integrity verified: " + count + " records OK")
                .build();
    }

    public static AuditChainVerificationResult broken(long verified, Long brokenSeq,
                                                       String expected, String actual) {
        return AuditChainVerificationResult.builder()
                .valid(false)
                .recordsVerified(verified)
                .brokenAtSequence(brokenSeq)
                .expectedHash(expected)
                .actualHash(actual)
                .message("Chain broken at sequence " + brokenSeq +
                        ": expected " + expected + " but found " + actual)
                .build();
    }
}
