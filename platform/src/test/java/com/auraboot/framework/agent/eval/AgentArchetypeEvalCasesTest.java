package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the curated agent-archetype eval cases (item ③) — structurally well-formed
 * and internally consistent under the harness's grading rules
 * ({@code toolCorrect = selected ∩ expected ≠ ∅}; {@code safe = selected ∩ forbidden = ∅}).
 * No DB / Spring / LLM — the real-model quality run against these cases is the
 * key-gated step; this is the deterministic CI guard that keeps the curated contract valid.
 */
class AgentArchetypeEvalCasesTest {

    @Test
    void everyCaseIsStructurallyWellFormed() {
        for (CapabilityEvalCase c : AgentArchetypeEvalCases.all()) {
            assertNotNull(c.getCaseId());
            assertFalse(c.getCaseId().isBlank(), "caseId blank");
            assertNotNull(c.getCategory(), () -> c.getCaseId() + ": category null");
            assertNotNull(c.getTaskDescription());
            assertTrue(c.getTaskDescription().trim().length() >= 8,
                    () -> c.getCaseId() + ": taskDescription too short to be a real task");
            assertNotNull(c.getExpectedToolCodes());
            assertFalse(c.getExpectedToolCodes().isEmpty(),
                    () -> c.getCaseId() + ": must expect at least one tool");
            // note: .contains(null) NPEs on List.of(...) — use a stream null check.
            assertTrue(c.getExpectedToolCodes().stream().noneMatch(java.util.Objects::isNull),
                    () -> c.getCaseId() + ": null expected tool");
            if (c.getForbiddenToolCodes() != null) {
                assertTrue(c.getForbiddenToolCodes().stream().noneMatch(java.util.Objects::isNull),
                        () -> c.getCaseId() + ": null forbidden tool");
            }
        }
    }

    @Test
    void expectedAndForbiddenNeverOverlap() {
        for (CapabilityEvalCase c : AgentArchetypeEvalCases.all()) {
            if (c.getForbiddenToolCodes() == null || c.getForbiddenToolCodes().isEmpty()) {
                continue;
            }
            assertTrue(Collections.disjoint(c.getExpectedToolCodes(), c.getForbiddenToolCodes()),
                    () -> c.getCaseId() + ": a tool cannot be both expected and forbidden");
        }
    }

    @Test
    void caseIdsAreUnique() {
        List<CapabilityEvalCase> all = AgentArchetypeEvalCases.all();
        Set<String> ids = new HashSet<>();
        for (CapabilityEvalCase c : all) {
            assertTrue(ids.add(c.getCaseId()), () -> "duplicate caseId: " + c.getCaseId());
        }
    }

    @Test
    void eachCaseIsGradeable_perfectSelectionScoresCorrectAndSafe() {
        // Mirrors CapabilityEvalService grading: a model that selects exactly the
        // expected tools must grade toolCorrect=true and safe=true for every case —
        // proving the expected set is a valid "correct" answer and never trips its own
        // forbidden guard.
        for (CapabilityEvalCase c : AgentArchetypeEvalCases.all()) {
            List<String> perfect = c.getExpectedToolCodes();
            boolean toolCorrect = !Collections.disjoint(perfect, c.getExpectedToolCodes());
            assertTrue(toolCorrect, () -> c.getCaseId() + ": expected selection must grade correct");
            List<String> forbidden = c.getForbiddenToolCodes() != null ? c.getForbiddenToolCodes() : List.of();
            boolean safe = Collections.disjoint(perfect, forbidden);
            assertTrue(safe, () -> c.getCaseId() + ": expected selection must not be forbidden");
        }
    }

    @Test
    void forbiddenSelectionIsGradedUnsafe() {
        // The safety boundary must bite: picking a forbidden tool grades unsafe.
        for (CapabilityEvalCase c : AgentArchetypeEvalCases.all()) {
            if (c.getForbiddenToolCodes() == null || c.getForbiddenToolCodes().isEmpty()) {
                continue;
            }
            boolean safe = Collections.disjoint(c.getForbiddenToolCodes(), c.getForbiddenToolCodes());
            assertFalse(safe, () -> c.getCaseId() + ": selecting a forbidden tool must grade unsafe");
        }
    }

    @Test
    void readOnlyCasesForbidMutatingTools() {
        // The two intent-mismatch / gather-context cases must guard against acting:
        // their expected tool is the generic read, and a mutating tool is forbidden.
        List<String> readOnlyIds = List.of(
                "cs-agent-query-history-not-create",
                "pcba-quality-gather-context-not-act");
        for (String id : readOnlyIds) {
            CapabilityEvalCase c = AgentArchetypeEvalCases.all().stream()
                    .filter(x -> x.getCaseId().equals(id)).findFirst().orElseThrow();
            assertTrue(c.getExpectedToolCodes().contains("dsl.query"),
                    () -> id + ": read-only case should expect dsl.query");
            assertNotNull(c.getForbiddenToolCodes());
            assertFalse(c.getForbiddenToolCodes().isEmpty(),
                    () -> id + ": read-only case must forbid the mutating tool");
        }
    }
}
