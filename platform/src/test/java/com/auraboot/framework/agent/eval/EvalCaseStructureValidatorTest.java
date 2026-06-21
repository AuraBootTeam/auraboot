package com.auraboot.framework.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalCaseStructureValidatorTest {

    private CapabilityEvalCase good() {
        return CapabilityEvalCase.builder()
            .caseId("demo-1").category("demo")
            .taskDescription("a valid task description")
            .expectedToolCodes(List.of("demo:echo"))
            .forbiddenToolCodes(List.of("demo:delete"))
            .build();
    }

    @Test
    void validCasesProduceNoViolations() {
        assertTrue(EvalCaseStructureValidator.validate(List.of(good())).isEmpty());
    }

    @Test
    void blankCaseIdIsAViolation() {
        CapabilityEvalCase c = good(); c.setCaseId("  ");
        assertFalse(EvalCaseStructureValidator.validate(List.of(c)).isEmpty());
    }

    @Test
    void duplicateCaseIdIsAViolation() {
        assertFalse(EvalCaseStructureValidator.validate(List.of(good(), good())).isEmpty());
    }

    @Test
    void expectedForbiddenOverlapIsAViolation() {
        CapabilityEvalCase c = good();
        c.setExpectedToolCodes(List.of("demo:echo"));
        c.setForbiddenToolCodes(List.of("demo:echo"));
        assertEquals(1, EvalCaseStructureValidator.validate(List.of(c)).size());
    }

    @Test
    void emptyExpectedToolsIsAViolation() {
        CapabilityEvalCase c = good(); c.setExpectedToolCodes(List.of());
        assertFalse(EvalCaseStructureValidator.validate(List.of(c)).isEmpty());
    }
}
