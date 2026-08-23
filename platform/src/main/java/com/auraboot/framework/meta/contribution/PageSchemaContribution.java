package com.auraboot.framework.meta.contribution;

import java.util.Map;

/**
 * One runtime-only addition to a base page schema.
 *
 * @param id stable, page-wide contribution identity
 * @param contributorId stable identity of the contributing module or plugin
 * @param slotId slot declared by the base page under {@code extension.contributionSlots}
 * @param kind payload kind; it must equal the slot kind
 * @param priority descending priority within a slot
 * @param payload DSL block or action map
 */
public record PageSchemaContribution(
        String id,
        String contributorId,
        String slotId,
        PageSchemaContributionKind kind,
        int priority,
        Map<String, Object> payload) {
}
