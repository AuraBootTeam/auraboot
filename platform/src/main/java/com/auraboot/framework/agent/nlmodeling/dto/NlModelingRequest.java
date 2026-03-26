package com.auraboot.framework.agent.nlmodeling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for NL Modeling generate endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NlModelingRequest {

    /** Natural language description of the desired module */
    private String description;

    /** Generation options */
    private Options options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Options {
        @Builder.Default
        private boolean generatePages = true;
        @Builder.Default
        private boolean generateCommands = true;
        @Builder.Default
        private boolean generateMenus = true;
        @Builder.Default
        private boolean generateI18n = true;
        @Builder.Default
        private boolean generateBindings = true;
    }
}
