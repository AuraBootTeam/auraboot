package com.auraboot.framework.plugin.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated result of all plugin validation checks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginValidationResult {

    /** Whether the plugin passed all error-level checks. */
    private boolean valid;

    /** All validation messages across all validators. */
    @Builder.Default
    private List<PluginValidationMessage> messages = new ArrayList<>();

    /** Summary counts by severity. */
    private int errorCount;
    private int warningCount;
    private int infoCount;

    public void addMessage(PluginValidationMessage msg) {
        messages.add(msg);
        if (msg.isError()) {
            errorCount++;
            valid = false;
        } else if (msg.isWarning()) {
            warningCount++;
        } else {
            infoCount++;
        }
    }

    public void addAll(List<PluginValidationMessage> msgs) {
        for (PluginValidationMessage msg : msgs) {
            addMessage(msg);
        }
    }

    public static PluginValidationResult empty() {
        return PluginValidationResult.builder().valid(true).build();
    }
}
