package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.ConditionNode.CompareNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Operand.LiteralOperand;
import com.auraboot.framework.decision.ast.Operand.PathOperand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicy;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyAction;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EventPolicy orchestration core: matchMode, dedup, conflict, idempotency, ordering (docs/2.md). */
class EventPolicyEvaluatorTest {

    private final EventPolicyEvaluator evaluator = new EventPolicyEvaluator();

    private static CompareNode eq(String path, Object val, DataType dt) {
        return CompareNode.of(new PathOperand(Scope.RECORD, "data." + path, dt), Operator.EQ, new LiteralOperand(val, dt));
    }

    private static CompareNode gt(String path, Object val) {
        return CompareNode.of(new PathOperand(Scope.RECORD, "data." + path, DataType.DECIMAL),
                Operator.GT, new LiteralOperand(val, DataType.DECIMAL));
    }

    private static PolicyAction notify(String role) {
        return new PolicyAction("NOTIFY", "ROLE:" + role, 10,
                Map.of("templateCode", "t"), "${record.entityCode}:${record.recordPid}:${rule.ruleCode}:${action.type}");
    }

    private static PolicyRule rule(String code, int priority, ConditionNode cond, PolicyAction... actions) {
        return new PolicyRule(code, code, priority, true, cond, List.of(actions));
    }

    private static PolicyRule decisionRule(String code, int priority, DecisionBinding binding, PolicyAction... actions) {
        return new PolicyRule(code, code, priority, true, null, binding, List.of(actions));
    }

    private EventPolicy policy(MatchMode mode, List<PolicyRule> rules) {
        return policy(mode, DedupStrategy.BY_IDEMPOTENCY_KEY, ConflictStrategy.REJECT_ON_CONFLICT, rules);
    }

    private EventPolicy policy(MatchMode mode, DedupStrategy dedup, ConflictStrategy conflict, List<PolicyRule> rules) {
        return new EventPolicy("p", "p", "FORM_SUBMITTED", "FORM", "complaint", PolicyPhase.AFTER_COMMIT,
                mode, ExecutionMode.ORDERED, FailureStrategy.RETRY_ASYNC, conflict, dedup, true, rules);
    }

    private DecisionContext complaintCtx(Map<String, Object> data) {
        return DecisionContext.builder()
                .put(Scope.RECORD, Map.of("entityCode", "complaint", "recordPid", "CMP-1", "data", data))
                .build();
    }

    @Test
    void collectAll_mockupS1_threeRulesMatch() {
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(
                rule("R-101", 100, eq("priority", "HIGH", DataType.ENUM), notify("support_manager")),
                rule("R-102", 200, gt("amount", 10000), new PolicyAction("START_PROCESS", "BPM:approval", 10, Map.of(),
                        "${record.recordPid}:${rule.ruleCode}")),
                rule("R-103", 300, eq("customerLevel", "VIP", DataType.ENUM),
                        new PolicyAction("CREATE_TASK", "ROLE:vip_agent", 10, Map.of(), "${record.recordPid}:${rule.ruleCode}"))));

        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(
                Map.of("priority", "HIGH", "amount", 20000, "customerLevel", "VIP")));

        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(r.matchedRuleCodes()).containsExactly("R-101", "R-102", "R-103");
        assertThat(r.actionPlans()).extracting(ResolvedActionPlan::type)
                .containsExactly("NOTIFY", "START_PROCESS", "CREATE_TASK"); // ordered by rule priority
        assertThat(r.actionPlans().get(0).idempotencyKey()).isEqualTo("complaint:CMP-1:R-101:NOTIFY");
    }

    @Test
    void firstMatch_stopsAtFirst() {
        EventPolicy p = policy(MatchMode.FIRST_MATCH, List.of(
                rule("R-1", 100, gt("amount", 10000), notify("a")),
                rule("R-2", 200, eq("priority", "HIGH", DataType.ENUM), notify("b"))));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000, "priority", "HIGH")));
        assertThat(r.matchedRuleCodes()).containsExactly("R-1");
    }

    @Test
    void unique_multipleMatches_isError() {
        EventPolicy p = policy(MatchMode.UNIQUE, List.of(
                rule("R-1", 100, gt("amount", 10000), notify("a")),
                rule("R-2", 200, eq("priority", "HIGH", DataType.ENUM), notify("b"))));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000, "priority", "HIGH")));
        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.ERROR);
    }

    @Test
    void notMatched_whenNoRuleTrue() {
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(rule("R-1", 100, gt("amount", 10000), notify("a"))));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 5)));
        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(r.actionPlans()).isEmpty();
    }

    @Test
    void disabledRuleSkipped() {
        PolicyRule disabled = new PolicyRule("R-D", "R-D", 50, false, gt("amount", 1), List.of(notify("a")));
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(disabled, rule("R-1", 100, gt("amount", 10000), notify("b"))));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000)));
        assertThat(r.skippedRuleCodes()).contains("R-D");
        assertThat(r.matchedRuleCodes()).containsExactly("R-1");
    }

    @Test
    void dedup_byActionTypeAndTarget_collapsesDuplicateNotify() {
        EventPolicy p = policy(MatchMode.COLLECT_ALL, DedupStrategy.BY_ACTION_TYPE_AND_TARGET,
                ConflictStrategy.REJECT_ON_CONFLICT, List.of(
                        rule("R-1", 100, gt("amount", 10000), notify("support_manager")),
                        rule("R-2", 200, eq("priority", "HIGH", DataType.ENUM), notify("support_manager"))));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000, "priority", "HIGH")));
        assertThat(r.actionPlans()).hasSize(1); // same NOTIFY + ROLE:support_manager collapsed
    }

    @Test
    void conflict_rejectOnConflict_whenTwoRulesPatchSameFieldDifferently() {
        PolicyAction patchHigh = new PolicyAction("PATCH_RECORD", "record", 10,
                Map.of("fieldPath", "priority", "value", "HIGH"), "${rule.ruleCode}:patch");
        PolicyAction patchLow = new PolicyAction("PATCH_RECORD", "record", 10,
                Map.of("fieldPath", "priority", "value", "LOW"), "${rule.ruleCode}:patch");
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(
                rule("R-1", 100, gt("amount", 10000), patchHigh),
                rule("R-2", 200, eq("customerLevel", "VIP", DataType.ENUM), patchLow)));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000, "customerLevel", "VIP")));
        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.CONFLICT);
        assertThat(r.errors()).anyMatch(e -> e.contains("conflicting writes"));
    }

    @Test
    void ordering_byRulePriorityThenActionOrder() {
        PolicyAction a1 = new PolicyAction("NOTIFY", "ROLE:x", 30, Map.of(), "${rule.ruleCode}:n");
        PolicyAction a2 = new PolicyAction("CREATE_SLA", "SLA:s", 20, Map.of(), "${rule.ruleCode}:s");
        // single rule, two actions: order 20 before 30
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(rule("R-1", 100, gt("amount", 1), a1, a2)));
        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000)));
        assertThat(r.actionPlans()).extracting(ResolvedActionPlan::type).containsExactly("CREATE_SLA", "NOTIFY");
    }

    @Test
    void decisionBindingOnlyRuleMatchesWhenInjectedMatcherReturnsTrue() {
        EventPolicyEvaluator decisionEvaluator = new EventPolicyEvaluator((policy, rule, context) -> Truth.TRUE);
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(
                decisionRule("R-D", 100, decisionBinding("decision.large_amount"), notify("decision"))));

        EventPolicyResult r = decisionEvaluator.evaluate(p, complaintCtx(Map.of("amount", 20000)));

        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(r.matchedRuleCodes()).containsExactly("R-D");
        assertThat(r.actionPlans()).extracting(ResolvedActionPlan::type).containsExactly("NOTIFY");
    }

    @Test
    void decisionBindingOnlyRuleDoesNotMatchWithoutRuntimeMatcher() {
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(
                decisionRule("R-D", 100, decisionBinding("decision.large_amount"), notify("decision"))));

        EventPolicyResult r = evaluator.evaluate(p, complaintCtx(Map.of("amount", 20000)));

        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(r.matchedRuleCodes()).isEmpty();
    }

    @Test
    void conditionAndDecisionBindingUseAndSemantics() {
        EventPolicyEvaluator decisionEvaluator = new EventPolicyEvaluator((policy, rule, context) -> Truth.FALSE);
        PolicyRule guarded = new PolicyRule(
                "R-G",
                "R-G",
                100,
                true,
                gt("amount", 10000),
                decisionBinding("decision.guard"),
                List.of(notify("guarded")));
        EventPolicy p = policy(MatchMode.COLLECT_ALL, List.of(guarded));

        EventPolicyResult r = decisionEvaluator.evaluate(p, complaintCtx(Map.of("amount", 20000)));

        assertThat(r.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(r.actionPlans()).isEmpty();
    }

    private static DecisionBinding decisionBinding(String code) {
        return new DecisionBinding(
                code,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                true,
                null,
                null);
    }
}
