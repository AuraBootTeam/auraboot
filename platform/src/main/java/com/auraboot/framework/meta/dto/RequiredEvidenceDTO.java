package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Required evidence definition within a Decision Definition.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequiredEvidenceDTO {

    /**
     * Evidence code (e.g. "payment_confirmed", "credit_score").
     */
    private String code;

    private String displayName;

    private String description;

    /**
     * Evidence must arrive within this time (minutes). 0 = no timeout.
     */
    private int timeoutMinutes;
}
