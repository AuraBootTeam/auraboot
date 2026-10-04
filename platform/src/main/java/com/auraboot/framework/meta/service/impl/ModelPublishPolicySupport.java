package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stable policy codes and backward-compatible publish governance descriptions. */
final class ModelPublishPolicySupport {
    private ModelPublishPolicySupport() {}

    private static final Map<String, String> MIGRATION_TEXT = Map.of(
            "NO_SCHEMA_MIGRATION", "No physical schema migration is required. Rebuild the Rule Center usage index if field metadata changed without DDL.",
            "CREATE_TABLE", "Create the physical table and generated indexes before enabling runtime writes.",
            "ADD_COLUMN", "Backfill new columns or define defaults before routing rules to the new field.",
            "ALTER_COLUMN_TYPE", "Validate data casts and replay affected rules against representative records before promotion.",
            "DROP_COLUMN", "Retire or migrate every affected rule consumer before removing the column.",
            "NULLABILITY", "Check existing rows against required/nullability changes before publish.",
            "REPLAY_CONSUMERS", "Confirm Rule Center blast radius and republish or replay affected BPM, SLA, Automation, EventPolicy and decision versions.",
            "REVIEW_DDL", "Review generated DDL and run a post-publish schema sync smoke.");

    static List<String> migrationSteps(List<String> schemaChangeKinds, List<DecisionFieldImpactDTO> fieldImpacts) {
        if (schemaChangeKinds == null || schemaChangeKinds.isEmpty()) {
            return List.of("NO_SCHEMA_MIGRATION");
        }
        List<String> steps = new ArrayList<>();
        if (schemaChangeKinds.contains("CREATE_TABLE")) {
            steps.add("CREATE_TABLE");
        }
        if (schemaChangeKinds.contains("ADD_COLUMN")) {
            steps.add("ADD_COLUMN");
        }
        if (schemaChangeKinds.contains("ALTER_COLUMN_TYPE")) {
            steps.add("ALTER_COLUMN_TYPE");
        }
        if (schemaChangeKinds.contains("DROP_COLUMN")) {
            steps.add("DROP_COLUMN");
        }
        if (schemaChangeKinds.contains("NULLABILITY")) {
            steps.add("NULLABILITY");
        }
        if (fieldImpacts != null && !fieldImpacts.isEmpty()) {
            steps.add("REPLAY_CONSUMERS");
        }
        if (steps.isEmpty()) {
            steps.add("REVIEW_DDL");
        }
        return List.copyOf(steps);
    }

    static String migrationText(List<String> steps) {
        return String.join(" ", steps.stream().map(MIGRATION_TEXT::get).toList());
    }

    static String historyCode(Integer latestPublishedVersion) {
        return latestPublishedVersion == null ? "INITIAL_PUBLISH" : "LATEST_COMPATIBLE";
    }

    static String historyText(Integer latestPublishedVersion) {
        return latestPublishedVersion == null
                ? "Initial publish: no historical published model version exists. Rule consumers should bind to this published schema after publish."
                : "Latest-compatible policy: publishing this draft makes it the current model metadata. Existing published rule, BPM, SLA, Automation and EventPolicy versions keep their own versioned assets, but consumers using latest model fields must be replayed and republished after acknowledgement.";
    }
}
