package com.auraboot.framework.meta.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Governed list export request.
 *
 * <p>The scope is explicit so a client cannot accidentally turn a page export into a
 * whole-dataset export. Record and field authorization remain server responsibilities.</p>
 */
@Data
public class DynamicDataExportRequest {

    @NotNull
    private Scope scope;

    @Pattern(regexp = "(?i)excel|xlsx|csv|json")
    private String format = "excel";

    @Size(max = 500)
    private List<@NotBlank @Size(max = 128) String> fields;

    @Size(max = 500)
    private List<@NotBlank @Size(max = 128) String> selectedPids;

    @Valid
    @Size(max = 100)
    private List<Condition> conditions;

    @Size(max = 500)
    private String keyword;

    public enum Scope {
        selected,
        filtered
    }

    @Data
    public static class Condition {
        @NotBlank
        @Size(max = 128)
        private String field;

        @NotBlank
        @Size(max = 32)
        private String operator;

        private Object value;
    }
}
