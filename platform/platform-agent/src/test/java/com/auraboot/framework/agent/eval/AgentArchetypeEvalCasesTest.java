package com.auraboot.framework.agent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The eval mechanism is validated over a vertical-free fixture. Vertical
 *  archetype cases now live in their plugins (ab_agent_eval_case) and are
 *  checked at import time by the same EvalCaseStructureValidator. */
class AgentArchetypeEvalCasesTest {

    @Test
    void genericFixtureIsStructurallyWellFormed() {
        List<String> violations = EvalCaseStructureValidator.validate(GenericEvalCaseFixture.cases());
        assertTrue(violations.isEmpty(), () -> "fixture violations: " + violations);
    }
}
