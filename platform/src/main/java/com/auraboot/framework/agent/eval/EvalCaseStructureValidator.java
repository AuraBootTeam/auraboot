package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural validation for eval cases. Used by OSS deterministic
 *  tests (over GenericEvalCaseFixture) and as a plugin import-time gate. */
public final class EvalCaseStructureValidator {

    private EvalCaseStructureValidator() {}

    public static List<String> validate(List<CapabilityEvalCase> cases) {
        List<String> violations = new ArrayList<>();
        if (cases == null) {
            return violations;
        }
        Set<String> seen = new HashSet<>();
        for (CapabilityEvalCase c : cases) {
            String id = c.getCaseId();
            if (id == null || id.isBlank()) {
                violations.add("caseId must be non-blank");
                continue;
            }
            if (!seen.add(id)) {
                violations.add(id + ": duplicate caseId");
            }
            if (c.getCategory() == null || c.getCategory().isBlank()) {
                violations.add(id + ": category must be non-blank");
            }
            if (c.getTaskDescription() == null || c.getTaskDescription().trim().length() < 8) {
                violations.add(id + ": taskDescription must be >= 8 chars");
            }
            List<String> expected = c.getExpectedToolCodes();
            if (expected == null || expected.isEmpty()) {
                violations.add(id + ": expectedToolCodes must be non-empty");
            } else if (expected.stream().anyMatch(e -> e == null)) {
                violations.add(id + ": expectedToolCodes contains null");
            }
            List<String> forbidden = c.getForbiddenToolCodes();
            if (forbidden != null && forbidden.stream().anyMatch(e -> e == null)) {
                violations.add(id + ": forbiddenToolCodes contains null");
            }
            if (expected != null && !expected.isEmpty() && forbidden != null) {
                Set<String> expectedSet = new HashSet<>(expected);
                if (forbidden.stream().anyMatch(expectedSet::contains)) {
                    violations.add(id + ": expected and forbidden tool codes overlap");
                }
            }
        }
        return violations;
    }
}
