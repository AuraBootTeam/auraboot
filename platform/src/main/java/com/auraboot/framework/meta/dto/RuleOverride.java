package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Command-level rule override.
 * Extends CrossFieldRule with a 'disabled' flag.
 *
 * Merge strategy:
 * - disabled=true → remove the rule (other fields ignored)
 * - matching id, no disabled → replace rule entirely
 * - new id → append as new rule
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RuleOverride extends CrossFieldRule {

    /** true = remove this rule from the final rule set */
    private Boolean disabled;
}
