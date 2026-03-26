package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.DecisionDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionDefinition Integration Test
 *
 * Covers P2-3 requirements:
 * 1. Decision definition CRUD
 * 2. Required evidence configuration
 * 3. Outcome options with auto-transition
 * 4. Invariant rules binding
 * 5. Publishing and versioning
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DecisionDefinition Integration Test - P2-3")
class DecisionDefinitionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DecisionDefinitionService decisionDefinitionService;

    private static final String TEST_SUBJECT_TYPE = "LoanApplication";

    /**
     * Helper method to create a decision for testing
     */
    private DecisionDefinition createTestDecision(String suffix) {
        String code = "dec_test_" + System.currentTimeMillis() + "_" + suffix;
        
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test Decision " + suffix);
        request.setDescription("Decision for testing");
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setStage("review");

        // Required evidence
        RequiredEvidenceDTO evidence = new RequiredEvidenceDTO();
        evidence.setCode("test_evidence");
        evidence.setDisplayName("Test Evidence");
        evidence.setDescription("Test evidence description");
        evidence.setTimeoutMinutes(60);
        request.setRequiredEvidence(List.of(evidence));

        // Outcome options
        DecisionOutcomeDTO outcome1 = new DecisionOutcomeDTO();
        outcome1.setCode("approved");
        outcome1.setDisplayName("Approve");

        DecisionOutcomeDTO outcome2 = new DecisionOutcomeDTO();
        outcome2.setCode("rejected");
        outcome2.setDisplayName("Reject");

        request.setOutcomeOptions(List.of(outcome1, outcome2));
        request.setAutoAdjudicate(false);

        return decisionDefinitionService.create(request);
    }

    // ==================== CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("P2-3.1: Create decision definition")
    void test01_createDecision() {
        String code = "dec_create_" + System.currentTimeMillis();
        
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Loan Approval Decision");
        request.setDescription("Decision for loan application approval");
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setStage("review");

        // Required evidence
        RequiredEvidenceDTO evidence1 = new RequiredEvidenceDTO();
        evidence1.setCode("credit_score");
        evidence1.setDisplayName("Credit Score");
        evidence1.setDescription("Credit bureau score report");
        evidence1.setTimeoutMinutes(60);

        RequiredEvidenceDTO evidence2 = new RequiredEvidenceDTO();
        evidence2.setCode("income_verification");
        evidence2.setDisplayName("Income Verification");
        evidence2.setDescription("Verified income documents");
        evidence2.setTimeoutMinutes(0);

        request.setRequiredEvidence(List.of(evidence1, evidence2));

        // Invariant rules
        InvariantRuleDTO rule1 = new InvariantRuleDTO();
        rule1.setName("credit_score_sufficient");
        rule1.setExpression("#evidence['credit_score'].score > 650");
        rule1.setSeverity("error");
        rule1.setDescription("Credit score must be above 650");

        request.setInvariants(List.of(rule1));

        // Outcome options
        DecisionOutcomeDTO outcome1 = new DecisionOutcomeDTO();
        outcome1.setCode("approved");
        outcome1.setDisplayName("Approve");
        outcome1.setDescription("Approve the loan application");
        outcome1.setAutoTransitionCommand("approve_loan");

        DecisionOutcomeDTO outcome2 = new DecisionOutcomeDTO();
        outcome2.setCode("rejected");
        outcome2.setDisplayName("Reject");
        outcome2.setDescription("Reject the loan application");

        request.setOutcomeOptions(List.of(outcome1, outcome2));
        request.setAutoAdjudicate(false);

        DecisionDefinition result = decisionDefinitionService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertEquals(code, result.getCode());

        log.info("Created decision: pid={}, code={}", result.getPid(), code);
    }

    @Test
    @Order(2)
    @DisplayName("P2-3.1: Get decision by PID")
    void test02_getByPid() {
        // Create a decision first
        DecisionDefinition created = createTestDecision("getByPid");
        assertNotNull(created.getPid());

        DecisionDefinition result = decisionDefinitionService.getByPid(created.getPid());

        assertNotNull(result);
        assertEquals(created.getPid(), result.getPid());
    }

    @Test
    @Order(3)
    @DisplayName("P2-3.1: Get decision by code")
    void test03_getCurrentByCode() {
        // Create a decision first
        DecisionDefinition created = createTestDecision("getByCode");

        DecisionDefinition result = decisionDefinitionService.getCurrentByCode(created.getCode());

        assertNotNull(result);
        assertEquals(created.getCode(), result.getCode());
    }

    @Test
    @Order(4)
    @DisplayName("P2-3.1: List decisions by subject type")
    void test04_listBySubjectType() {
        // Create a decision first
        DecisionDefinition created = createTestDecision("listBySubject");

        List<DecisionDefinition> decisions = decisionDefinitionService.listBySubjectType(TEST_SUBJECT_TYPE);

        assertNotNull(decisions);
        assertTrue(decisions.stream().anyMatch(d -> created.getCode().equals(d.getCode())));
    }

    @Test
    @Order(5)
    @DisplayName("P2-3.1: Update decision definition")
    void test05_updateDecision() {
        // Create a decision first
        DecisionDefinition created = createTestDecision("update");
        assertNotNull(created.getPid());

        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode(created.getCode());
        request.setDisplayName("Updated Decision");
        request.setDescription("Updated description");
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setStage("review");
        request.setRequiredEvidence(List.of());
        request.setOutcomeOptions(List.of());
        request.setAutoAdjudicate(true);

        DecisionDefinition result = decisionDefinitionService.update(created.getPid(), request);

        assertNotNull(result);
        assertEquals("Updated Decision", result.getDisplayName());
    }

    // ==================== Publish Tests ====================

    @Test
    @Order(10)
    @DisplayName("P2-3.2: Publish decision definition")
    void test10_publishDecision() {
        // Create a decision first
        DecisionDefinition created = createTestDecision("publish");
        assertNotNull(created.getPid());

        assertDoesNotThrow(() -> {
            decisionDefinitionService.publish(created.getPid());
        });
    }

    // ==================== Minimal Decision Tests ====================

    @Test
    @Order(20)
    @DisplayName("P2-3: Create decision with minimal fields")
    void test20_createMinimalDecision() {
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode("dec_minimal_" + System.currentTimeMillis());
        request.setSubjectType("SimpleSubject");
        request.setStage("check");
        request.setRequiredEvidence(List.of());

        DecisionOutcomeDTO yes = new DecisionOutcomeDTO();
        yes.setCode("yes");
        yes.setDisplayName("Yes");

        DecisionOutcomeDTO no = new DecisionOutcomeDTO();
        no.setCode("NO");
        no.setDisplayName("No");

        request.setOutcomeOptions(List.of(yes, no));

        DecisionDefinition result = decisionDefinitionService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
    }

    @Test
    @Order(21)
    @DisplayName("P2-3: Create decision with auto-adjudicate enabled")
    void test21_createAutoAdjudicateDecision() {
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode("dec_auto_" + System.currentTimeMillis());
        request.setSubjectType("AutoSubject");
        request.setStage("auto_check");
        request.setRequiredEvidence(List.of());
        request.setAutoAdjudicate(true);

        DecisionOutcomeDTO pass = new DecisionOutcomeDTO();
        pass.setCode("pass");
        pass.setDisplayName("Pass");

        DecisionOutcomeDTO fail = new DecisionOutcomeDTO();
        fail.setCode("fail");
        fail.setDisplayName("Fail");

        request.setOutcomeOptions(List.of(pass, fail));

        DecisionDefinition result = decisionDefinitionService.create(request);

        assertNotNull(result);
    }

    // ==================== Delete Tests ====================

    @Test
    @Order(90)
    @DisplayName("P2-3.1: Delete decision definition")
    void test90_deleteDecision() {
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode("dec_delete_" + System.currentTimeMillis());
        request.setSubjectType("DeleteSubject");
        request.setStage("delete_stage");
        request.setRequiredEvidence(List.of());
        request.setOutcomeOptions(List.of());

        DecisionDefinition created = decisionDefinitionService.create(request);
        assertNotNull(created.getPid());

        assertDoesNotThrow(() -> {
            decisionDefinitionService.delete(created.getPid());
        });
    }

    @Test
    @Order(91)
    @DisplayName("P2-3.1: Delete non-existent decision should throw")
    void test91_deleteDecision_notFound() {
        assertThrows(Exception.class, () -> {
            decisionDefinitionService.delete("non_existent_pid");
        });
    }
}
