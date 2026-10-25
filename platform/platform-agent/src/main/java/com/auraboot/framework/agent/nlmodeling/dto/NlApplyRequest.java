package com.auraboot.framework.agent.nlmodeling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for applying generated DSL as a plugin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NlApplyRequest {

    /** Plugin code (used as pluginId and namespace) */
    private String pluginCode;

    /** Generated resources to apply */
    private NlModelingResponse.Resources resources;
}
