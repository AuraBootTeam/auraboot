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
        // Boxed Boolean (not primitive boolean): with @Builder.Default the field
        // initializer is stripped from the no-args constructor Jackson uses, so a
        // primitive would deserialize to false for any OMITTED option — silently
        // disabling page/command/menu generation when a caller sends `options: {}`
        // or partial options. With Boolean, an omitted field stays null and is
        // treated as "generate" (true) at the read site; only an explicit `false`
        // disables. The builder still defaults to true.
        @Builder.Default
        private Boolean generatePages = true;
        @Builder.Default
        private Boolean generateCommands = true;
        @Builder.Default
        private Boolean generateMenus = true;
        @Builder.Default
        private Boolean generateI18n = true;
        @Builder.Default
        private Boolean generateBindings = true;
    }
}
