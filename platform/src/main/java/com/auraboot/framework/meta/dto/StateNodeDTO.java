package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * State node definition within a State Graph.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateNodeDTO {

    /**
     * Unique code for this state node (e.g. "pending", "approved").
     */
    private String code;

    /**
     * Display name for UI rendering.
     */
    private String displayName;

    /**
     * Node type: INITIAL / NORMAL / TERMINAL.
     */
    private String type;

    /**
     * Optional description.
     */
    private String description;

    /**
     * Custom metadata for UI/rendering config.
     */
    private Map<String, Object> metadata;

    /**
     * Invariant codes bound to this state node.
     * Evaluated when record is in this state.
     */
    private List<String> invariants;
}
