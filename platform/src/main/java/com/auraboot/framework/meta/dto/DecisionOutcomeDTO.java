package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Possible decision outcome option.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionOutcomeDTO {

    /**
     * Outcome code (e.g. "approved", "rejected", "escalated").
     */
    private String code;

    private String displayName;

    private String description;

    /**
     * Optional: Command code to trigger automatically when this outcome is decided.
     */
    private String autoTransitionCommand;
}
