package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * State transition rule within a State Graph.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateTransitionDTO {

    /**
     * Source node code.
     */
    private String from;

    /**
     * Target node code.
     */
    private String to;

    /**
     * Command code that triggers this transition.
     */
    private String triggerCommand;

    /**
     * SpEL expression for precondition guard.
     */
    private String guard;

    /**
     * Display name for UI edge label.
     */
    private String displayName;

    /**
     * Optional description.
     */
    private String description;

    /**
     * Custom metadata.
     */
    private Map<String, Object> metadata;
}
