package com.auraboot.module.meta.excel;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side import contract resolved from a model's {@code extension.importPolicy}.
 *
 * <p>The browser DSL controls whether an entry point is rendered. This policy is the
 * authoritative boundary: it decides which modes and match keys are legal and which
 * business commands must execute each imported row.</p>
 */
@Data
@Builder
public class ExcelImportPolicy {

    private String modelCode;
    private boolean enabled;
    @Builder.Default
    private Set<String> modes = Set.of("insert");
    @Builder.Default
    private List<String> updateKeys = List.of();
    private String createCommand;
    private String updateCommand;
    @Builder.Default
    private Set<String> createFields = Set.of();
    @Builder.Default
    private Set<String> createAutoSetFields = Set.of();
    @Builder.Default
    private Set<String> updateFields = Set.of();

    public boolean supports(String mode) {
        return mode != null && modes.contains(mode.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> templateFields() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(updateKeys);
        result.addAll(createFields);
        return result;
    }
}
