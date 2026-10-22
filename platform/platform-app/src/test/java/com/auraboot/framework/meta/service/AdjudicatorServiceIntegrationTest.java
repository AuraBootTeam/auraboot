package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.DecisionRecord;
import com.auraboot.framework.meta.entity.EvidenceRecord;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdjudicatorService Integration Test
 *
 * Covers P2-3 requirements (Decision Event):
 * 1. Evidence submission and collection
 * 2. Evidence completeness checking
 * 3. Manual adjudication
 * 4. Decision record retrieval
 * 5. Idempotent decision generation
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AdjudicatorService Integration Test - P2-3")
class AdjudicatorServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdjudicatorService adjudicatorService;

    @Autowired
    private DecisionDefinitionService decisionDefinitionService;

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_SUBJECT_TYPE = "TestApplication";
    private static final String TEST_STAGE = "review";

    /**
     * Get current tenant ID from MetaContext
     */
    private Long getCurrentTenantId() {
        return com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
    }

    /**
     * Helper method to create and publish a decision definition for tests
     */
    private String setupDecisionDefinition() {
        String code = "adj_test_dec_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        
        DecisionDefinitionCreateRequest request = new DecisionDefinitionCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test Adjudication Decision");
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setStage(TEST_STAGE);

        RequiredEvidenceDTO evidence1 = new RequiredEvidenceDTO();
        evidence1.setCode("document_check");
        evidence1.setDisplayName("Document Verification");
        evidence1.setTimeoutMinutes(30);

        RequiredEvidenceDTO evidence2 = new RequiredEvidenceDTO();
        evidence2.setCode("background_check");
        evidence2.setDisplayName("Background Check");
        evidence2.setTimeoutMinutes(60);

        request.setRequiredEvidence(List.of(evidence1, evidence2));

        DecisionOutcomeDTO approved = new DecisionOutcomeDTO();
        approved.setCode("approved");
        approved.setDisplayName("Approve");

        DecisionOutcomeDTO rejected = new DecisionOutcomeDTO();
        rejected.setCode("rejected");
        rejected.setDisplayName("Reject");

        request.setOutcomeOptions(List.of(approved, rejected));
        request.setAutoAdjudicate(false);

        decisionDefinitionService.create(request);
        decisionDefinitionService.publish(
                decisionDefinitionService.getCurrentByCode(code).getPid()
        );
        
        return code;
    }

    // ==================== Setup: Create Decision Definition ====================

    @Test
    @Order(1)
    @DisplayName("Setup: Create decision definition for adjudication tests")
    void test01_setupDecisionDefinition() {
        String code = setupDecisionDefinition();
        assertNotNull(code);
        log.info("Setup decision definition for adjudication tests: code={}", code);
    }

    // ==================== Evidence Submission Tests ====================

    @Test
    @Order(10)
    @DisplayName("P2-3.1: Submit first evidence")
    void test10_submitEvidence_first() {
        String testSubjectId = "app_" + System.currentTimeMillis();
        
        EvidenceSubmitRequest request = new EvidenceSubmitRequest();
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setSubjectId(testSubjectId);
        request.setStage(TEST_STAGE);
        request.setEvidenceCode("document_check");
        request.setEvidenceData(Map.of("status", "verified", "documentId", "doc_001"));
        request.setSource("document_service");

        EvidenceRecord result = adjudicatorService.submitEvidence(request);

        assertNotNull(result);
        assertEquals(TEST_SUBJECT_TYPE, result.getSubjectType());
        assertEquals(testSubjectId, result.getSubjectId());
        assertEquals(TEST_STAGE, result.getStage());
        assertEquals("document_check", result.getEvidenceCode());
        assertNotNull(result.getCollectedAt());

        log.info("Submitted first evidence: code={}", result.getEvidenceCode());
    }

    @Test
    @Order(11)
    @DisplayName("P2-3.1: Submit second evidence")
    void test11_submitEvidence_second() {
        String testSubjectId = "app_" + System.currentTimeMillis();
        
        EvidenceSubmitRequest request = new EvidenceSubmitRequest();
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setSubjectId(testSubjectId);
        request.setStage(TEST_STAGE);
        request.setEvidenceCode("background_check");
        request.setEvidenceData(Map.of("result", "clean", "score", 95));
        request.setSource("background_service");

        EvidenceRecord result = adjudicatorService.submitEvidence(request);

        assertNotNull(result);
        assertEquals("background_check", result.getEvidenceCode());
    }

    // ==================== Evidence Query Tests ====================

    @Test
    @Order(20)
    @DisplayName("P2-3.2: Get all evidence for subject")
    void test20_getEvidence() {
        // Setup: create definition and submit evidence in same transaction
        setupDecisionDefinition();
        String testSubjectId = "app_evidence_" + System.currentTimeMillis();
        
        // Submit two evidence records
        EvidenceSubmitRequest request1 = new EvidenceSubmitRequest();
        request1.setSubjectType(TEST_SUBJECT_TYPE);
        request1.setSubjectId(testSubjectId);
        request1.setStage(TEST_STAGE);
        request1.setEvidenceCode("document_check");
        request1.setEvidenceData(Map.of("status", "verified"));
        request1.setSource("document_service");
        adjudicatorService.submitEvidence(request1);
        
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(testSubjectId);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("background_check");
        request2.setEvidenceData(Map.of("result", "clean"));
        request2.setSource("background_service");
        adjudicatorService.submitEvidence(request2);
        
        // Query evidence
        List<EvidenceRecord> evidence = adjudicatorService.getEvidence(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId, TEST_STAGE);

        assertNotNull(evidence);
        assertTrue(evidence.size() >= 2, "Should have at least 2 evidence records");
    }

    @Test
    @Order(21)
    @DisplayName("P2-3.2: Get evidence for non-existent subject returns empty")
    void test21_getEvidence_empty() {
        List<EvidenceRecord> evidence = adjudicatorService.getEvidence(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, "non_existent_subject", TEST_STAGE);

        assertNotNull(evidence);
        assertTrue(evidence.isEmpty());
    }

    // ==================== Evidence Completeness Tests ====================

    @Test
    @Order(30)
    @DisplayName("P2-3.3: Check evidence completeness - all collected")
    void test30_isEvidenceComplete() {
        // Setup: create definition and submit all required evidence
        setupDecisionDefinition();
        String testSubjectId = "app_complete_" + System.currentTimeMillis();
        
        // Submit both required evidence
        EvidenceSubmitRequest request1 = new EvidenceSubmitRequest();
        request1.setSubjectType(TEST_SUBJECT_TYPE);
        request1.setSubjectId(testSubjectId);
        request1.setStage(TEST_STAGE);
        request1.setEvidenceCode("document_check");
        request1.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request1);
        
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(testSubjectId);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("background_check");
        request2.setEvidenceData(Map.of("result", "clean"));
        adjudicatorService.submitEvidence(request2);
        
        boolean complete = adjudicatorService.isEvidenceComplete(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId, TEST_STAGE);

        assertTrue(complete, "Evidence should be complete after submitting all required pieces");
    }

    @Test
    @Order(31)
    @DisplayName("P2-3.3: Check evidence completeness - incomplete")
    void test31_isEvidenceComplete_incomplete() {
        // Setup: create definition
        setupDecisionDefinition();
        String incompleteSubject = "incomplete_" + System.currentTimeMillis();

        // Submit only one evidence
        EvidenceSubmitRequest request = new EvidenceSubmitRequest();
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setSubjectId(incompleteSubject);
        request.setStage(TEST_STAGE);
        request.setEvidenceCode("document_check");
        request.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request);

        boolean complete = adjudicatorService.isEvidenceComplete(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, incompleteSubject, TEST_STAGE);

        assertFalse(complete, "Evidence should be incomplete with only one piece submitted");
    }

    // ==================== Adjudication Tests ====================

    @Test
    @Order(40)
    @DisplayName("P2-3.4: Adjudicate with all evidence collected")
    void test40_adjudicate() {
        // Setup: create definition and submit all evidence
        setupDecisionDefinition();
        String testSubjectId = "app_adjudicate_" + System.currentTimeMillis();
        
        // Submit both required evidence
        EvidenceSubmitRequest request1 = new EvidenceSubmitRequest();
        request1.setSubjectType(TEST_SUBJECT_TYPE);
        request1.setSubjectId(testSubjectId);
        request1.setStage(TEST_STAGE);
        request1.setEvidenceCode("document_check");
        request1.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request1);
        
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(testSubjectId);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("background_check");
        request2.setEvidenceData(Map.of("result", "clean"));
        adjudicatorService.submitEvidence(request2);
        
        DecisionRecord record = adjudicatorService.adjudicate(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId,
                TEST_STAGE, "approved", TEST_USER_ID);

        assertNotNull(record);
        assertEquals(TEST_SUBJECT_TYPE, record.getSubjectType());
        assertEquals(testSubjectId, record.getSubjectId());
        assertEquals(TEST_STAGE, record.getStage());
        assertEquals("approved", record.getOutcome());
        assertNotNull(record.getDecidedAt());
        assertEquals(TEST_USER_ID, record.getDecidedBy());

        log.info("Adjudicated: outcome={}", record.getOutcome());
    }

    @Test
    @Order(41)
    @DisplayName("P2-3.4: Get decision after adjudication")
    void test41_getDecision() {
        // Setup: create definition, submit evidence, and adjudicate
        setupDecisionDefinition();
        String testSubjectId = "app_get_decision_" + System.currentTimeMillis();
        
        // Submit both required evidence
        EvidenceSubmitRequest request1 = new EvidenceSubmitRequest();
        request1.setSubjectType(TEST_SUBJECT_TYPE);
        request1.setSubjectId(testSubjectId);
        request1.setStage(TEST_STAGE);
        request1.setEvidenceCode("document_check");
        request1.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request1);
        
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(testSubjectId);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("background_check");
        request2.setEvidenceData(Map.of("result", "clean"));
        adjudicatorService.submitEvidence(request2);
        
        // Adjudicate
        adjudicatorService.adjudicate(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId,
                TEST_STAGE, "approved", TEST_USER_ID);
        
        // Get decision
        DecisionRecord record = adjudicatorService.getDecision(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId, TEST_STAGE);

        assertNotNull(record);
        assertEquals("approved", record.getOutcome());
    }

    @Test
    @Order(42)
    @DisplayName("P2-3.4: Idempotent adjudication (same subject+stage)")
    void test42_adjudicate_idempotent() {
        // Setup: create definition, submit evidence, and adjudicate
        setupDecisionDefinition();
        String testSubjectId = "app_idempotent_" + System.currentTimeMillis();
        
        // Submit both required evidence
        EvidenceSubmitRequest request1 = new EvidenceSubmitRequest();
        request1.setSubjectType(TEST_SUBJECT_TYPE);
        request1.setSubjectId(testSubjectId);
        request1.setStage(TEST_STAGE);
        request1.setEvidenceCode("document_check");
        request1.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request1);
        
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(testSubjectId);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("background_check");
        request2.setEvidenceData(Map.of("result", "clean"));
        adjudicatorService.submitEvidence(request2);
        
        // First adjudication
        adjudicatorService.adjudicate(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId,
                TEST_STAGE, "approved", TEST_USER_ID);
        
        // Second adjudication for same subject should be idempotent
        DecisionRecord record = adjudicatorService.adjudicate(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId,
                TEST_STAGE, "rejected", TEST_USER_ID);

        // Should return existing decision (APPROVED) due to idempotency
        assertNotNull(record);
        assertEquals("approved", record.getOutcome(),
                "Idempotent adjudication should return original outcome");
    }

    @Test
    @Order(43)
    @DisplayName("P2-3.4: Get decision for non-existent subject returns null")
    void test43_getDecision_notFound() {
        DecisionRecord record = adjudicatorService.getDecision(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, "non_existent", TEST_STAGE);

        assertNull(record);
    }

    // ==================== Different Stage Tests ====================

    @Test
    @Order(50)
    @DisplayName("P2-3.5: Adjudicate different stage independently")
    void test50_differentStage() {
        String testSubjectId = "app_diff_stage_" + System.currentTimeMillis();
        String differentStage = "final_review";

        // Submit evidence for different stage
        EvidenceSubmitRequest request = new EvidenceSubmitRequest();
        request.setSubjectType(TEST_SUBJECT_TYPE);
        request.setSubjectId(testSubjectId);
        request.setStage(differentStage);
        request.setEvidenceCode("final_check");
        request.setEvidenceData(Map.of("result", "OK"));
        adjudicatorService.submitEvidence(request);

        // Different stage should have independent evidence
        List<EvidenceRecord> evidence = adjudicatorService.getEvidence(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, testSubjectId, differentStage);

        assertNotNull(evidence);
        assertEquals(1, evidence.size());
    }

    // ==================== Multiple Subjects Tests ====================

    @Test
    @Order(60)
    @DisplayName("P2-3.6: Different subjects have independent decisions")
    void test60_differentSubjects() {
        // Setup: create definition
        setupDecisionDefinition();
        String subject1 = "app_first_" + System.currentTimeMillis();
        String subject2 = "app_second_" + System.currentTimeMillis();
        
        // Submit evidence and adjudicate for subject1
        EvidenceSubmitRequest request1a = new EvidenceSubmitRequest();
        request1a.setSubjectType(TEST_SUBJECT_TYPE);
        request1a.setSubjectId(subject1);
        request1a.setStage(TEST_STAGE);
        request1a.setEvidenceCode("document_check");
        request1a.setEvidenceData(Map.of("status", "verified"));
        adjudicatorService.submitEvidence(request1a);
        
        EvidenceSubmitRequest request1b = new EvidenceSubmitRequest();
        request1b.setSubjectType(TEST_SUBJECT_TYPE);
        request1b.setSubjectId(subject1);
        request1b.setStage(TEST_STAGE);
        request1b.setEvidenceCode("background_check");
        request1b.setEvidenceData(Map.of("result", "clean"));
        adjudicatorService.submitEvidence(request1b);
        
        adjudicatorService.adjudicate(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, subject1,
                TEST_STAGE, "approved", TEST_USER_ID);

        // Submit only one evidence for subject2
        EvidenceSubmitRequest request2 = new EvidenceSubmitRequest();
        request2.setSubjectType(TEST_SUBJECT_TYPE);
        request2.setSubjectId(subject2);
        request2.setStage(TEST_STAGE);
        request2.setEvidenceCode("document_check");
        request2.setEvidenceData(Map.of("status", "failed"));
        adjudicatorService.submitEvidence(request2);

        // Subject1 should have its decision
        DecisionRecord original = adjudicatorService.getDecision(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, subject1, TEST_STAGE);
        assertNotNull(original);
        assertEquals("approved", original.getOutcome());

        // Subject2 should not have a decision yet
        DecisionRecord newDecision = adjudicatorService.getDecision(
                getCurrentTenantId(), TEST_SUBJECT_TYPE, subject2, TEST_STAGE);
        assertNull(newDecision);
    }
}
