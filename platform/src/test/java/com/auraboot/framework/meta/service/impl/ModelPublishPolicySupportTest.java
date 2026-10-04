package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.decision.dto.DecisionFieldImpactDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelPublishPolicySupportTest {
    @Test
    void noDdlKeepsIndexGuidanceEvenWhenConsumersExist() {
        assertEquals(List.of("NO_SCHEMA_MIGRATION"),
                ModelPublishPolicySupport.migrationSteps(List.of(), List.of(new DecisionFieldImpactDTO())));
        assertEquals("No physical schema migration is required. Rebuild the Rule Center usage index if field metadata changed without DDL.",
                ModelPublishPolicySupport.migrationText(List.of("NO_SCHEMA_MIGRATION")));
        assertEquals(List.of("NO_SCHEMA_MIGRATION"), ModelPublishPolicySupport.migrationSteps(null, null));
    }

    @Test
    void migrationCodesPreserveOrderingAndConsumerReview() {
        List<String> kinds = List.of("NULLABILITY", "DROP_COLUMN", "ALTER_COLUMN_TYPE", "ADD_COLUMN", "CREATE_TABLE");
        assertEquals(List.of("CREATE_TABLE", "ADD_COLUMN", "ALTER_COLUMN_TYPE", "DROP_COLUMN", "NULLABILITY", "REPLAY_CONSUMERS"),
                ModelPublishPolicySupport.migrationSteps(kinds, List.of(new DecisionFieldImpactDTO())));
        assertEquals(List.of("CREATE_TABLE"), ModelPublishPolicySupport.migrationSteps(List.of("CREATE_TABLE"), null));
        assertEquals("Create the physical table and generated indexes before enabling runtime writes. Check existing rows against required/nullability changes before publish.",
                ModelPublishPolicySupport.migrationText(List.of("CREATE_TABLE", "NULLABILITY")));
    }

    @Test
    void unclassifiedDdlStillRequiresPostPublishSync() {
        assertEquals(List.of("REVIEW_DDL"), ModelPublishPolicySupport.migrationSteps(List.of("FUTURE_CHANGE"), List.of()));
        assertEquals("Review generated DDL and run a post-publish schema sync smoke.",
                ModelPublishPolicySupport.migrationText(List.of("REVIEW_DDL")));
        assertEquals(List.of("REPLAY_CONSUMERS"),
                ModelPublishPolicySupport.migrationSteps(List.of("FUTURE_CHANGE"), List.of(new DecisionFieldImpactDTO())));
    }

    @Test
    void historyPolicyUsesTheSamePublishedVersionForCodeAndDescription() {
        assertEquals("INITIAL_PUBLISH", ModelPublishPolicySupport.historyCode(null));
        assertEquals("LATEST_COMPATIBLE", ModelPublishPolicySupport.historyCode(3));
        assertTrue(ModelPublishPolicySupport.historyText(null).startsWith("Initial publish:"));
        assertTrue(ModelPublishPolicySupport.historyText(3).startsWith("Latest-compatible policy:"));
    }
}
