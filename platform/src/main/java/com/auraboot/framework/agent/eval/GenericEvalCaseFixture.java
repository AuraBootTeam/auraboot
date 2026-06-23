package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.List;
import java.util.Map;

/** Vertical-free eval cases used only to self-test the eval mechanism in
 *  deterministic OSS CI. Contains NO business command codes — see
 *  scripts/check-agent-eval-boundary.mjs. */
public final class GenericEvalCaseFixture {

    private GenericEvalCaseFixture() {}

    public static List<CapabilityEvalCase> cases() {
        return List.of(
            CapabilityEvalCase.builder()
                .caseId("generic-read-not-write")
                .category("generic")
                .taskDescription("look up the current value of an item")
                .expectedToolCodes(List.of("demo:query"))
                .expectedInputKeys(Map.of())
                .forbiddenToolCodes(List.of("demo:delete"))
                .expectsConfirmation(false)
                .build(),
            CapabilityEvalCase.builder()
                .caseId("generic-action-with-confirm")
                .category("generic")
                .taskDescription("perform a guarded write action after confirmation")
                .expectedToolCodes(List.of("demo:write"))
                .expectedInputKeys(Map.of())
                .forbiddenToolCodes(List.of("demo:delete"))
                .expectsConfirmation(true)
                .build()
        );
    }
}
