package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionPlanResolverTest {
    private final ActionPlanResolver resolver = new ActionPlanResolver();

    private ActionPlanResolver.MatchedRuleActions update(String code, Map<String, Object> fields) {
        PolicyAction action = new PolicyAction("UPDATE_RECORD", "record", 1,
                Map.of("fields", fields), code);
        return new ActionPlanResolver.MatchedRuleActions(
                new PolicyRule(code, code, 1, true, null, List.of(action)), List.of(action));
    }

    @Test
    void rejectsConflictingFieldsInTheActualHandlerPayload() {
        var result = resolver.resolve(List.of(
                update("first", Map.of("status", "accepted", "note", "done")),
                update("second", Map.of("status", "rejected"))),
                DecisionContext.builder().build(), DedupStrategy.NONE, ConflictStrategy.REJECT_ON_CONFLICT);
        assertThat(result.conflict()).isTrue();
        assertThat(result.plans()).isEmpty();
        assertThat(result.conflicts()).containsExactly("conflicting writes to field 'record:status'");
    }

    @Test
    void allowsCompatibleAndDisjointWrites() {
        var result = resolver.resolve(List.of(
                update("first", Map.of("status", "accepted")),
                update("second", Map.of("status", "accepted", "note", "done"))),
                DecisionContext.builder().build(), DedupStrategy.NONE, ConflictStrategy.MERGE_IF_COMPATIBLE);
        assertThat(result.conflict()).isFalse();
        assertThat(result.plans()).hasSize(2);
    }

    @Test
    void incompatibleMergeDoesNotSilentlyUseLastWriter() {
        var result = resolver.resolve(List.of(
                update("first", Map.of("status", "accepted")),
                update("second", Map.of("status", "rejected"))),
                DecisionContext.builder().build(), DedupStrategy.NONE, ConflictStrategy.MERGE_IF_COMPATIBLE);
        assertThat(result.conflict()).isTrue();
        assertThat(result.plans()).isEmpty();
    }

    @Test
    void rejectsLegacyScalarShapeThatTheHandlerCannotExecute() {
        PolicyAction action = new PolicyAction("PATCH_RECORD", "record", 1,
                Map.of("field", "status", "value", "accepted"), "key");
        PolicyRule rule = new PolicyRule("r", "r", 1, true, null, List.of(action));
        var result = resolver.resolve(List.of(new ActionPlanResolver.MatchedRuleActions(rule, rule.actions())),
                DecisionContext.builder().build(), DedupStrategy.NONE, ConflictStrategy.REJECT_ON_CONFLICT);
        assertThat(result.conflict()).isTrue();
        assertThat(result.conflicts()).containsExactly("record update requires non-empty payload.fields");
    }
}
