package com.auraboot.module.meta.bitemporal;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Abstract base class for bitemporal entities.
 *
 * <p>Bitemporal modeling tracks two independent time dimensions:
 * <ul>
 *   <li><b>Valid time</b> (validFrom/validTo): when the fact is true in the real world</li>
 *   <li><b>Transaction time</b> (txnFrom/txnTo): when the fact is recorded in the system</li>
 * </ul>
 *
 * <p>A record is "current" when txnTo is NULL (i.e., it has not been superseded).
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
public abstract class BitemporalEntity {

    private Long id;

    /** Logical entity key (multiple rows may share the same entityKey across versions). */
    private Long entityKey;

    /** Start of the validity period (inclusive). */
    private LocalDate validFrom;

    /** End of the validity period (exclusive). */
    private LocalDate validTo;

    /** Transaction start time (when this version was recorded). */
    private Instant txnFrom;

    /** Transaction end time (null = current version; non-null = superseded). */
    private Instant txnTo;

    /** ID of the row that superseded this one, null if current. */
    private Long supersededBy;

    private Long tenantId;

    /**
     * A record is "current" if it has not been superseded (txnTo is null).
     */
    public boolean isCurrent() {
        return txnTo == null;
    }

    /** Sentinel date representing "forever" in the valid-time dimension. */
    public static final LocalDate MAX_VALID_DATE = LocalDate.of(9999, 12, 31);
}
