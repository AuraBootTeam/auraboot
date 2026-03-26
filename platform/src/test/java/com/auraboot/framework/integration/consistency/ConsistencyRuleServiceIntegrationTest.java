package com.auraboot.framework.integration.consistency;

import com.auraboot.framework.consistency.dao.mapper.ConsistencyRuleMapper;
import com.auraboot.framework.consistency.dto.*;
import com.auraboot.framework.consistency.entity.ConsistencyRule;
import com.auraboot.framework.consistency.exception.ConsistencyViolationException;
import com.auraboot.framework.consistency.service.ConsistencyRuleService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PaginationResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for ConsistencyRuleService.
 * Tests CRUD operations, validation logic, batch validation, and pipeline integration.
 */
@Slf4j
@Transactional
@Rollback(true)
@DisplayName("Consistency Rule Service Integration Tests")
public class ConsistencyRuleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConsistencyRuleService consistencyRuleService;

    @Autowired
    private ConsistencyRuleMapper consistencyRuleMapper;

    private ConsistencyRuleRequest buildRequest(String suffix) {
        ConsistencyRuleRequest request = new ConsistencyRuleRequest();
        request.setCode("test_rule_" + suffix + "_" + System.currentTimeMillis());
        request.setName("Test Rule " + suffix);
        request.setRuleType("cross_document");
        request.setSeverity("error");
        request.setSourceModel("shipment_line");
        request.setSourceField("quantity");
        request.setTargetModel("order_line");
        request.setTargetField("quantity");
        request.setLinkField("order_line_id");
        request.setAggregation("sum");
        request.setOperator("LE");
        request.setMessageTemplate("Shipped qty ({sourceSum}) exceeds ordered qty ({targetValue})");
        request.setEnabled(true);
        return request;
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudTests {

        @Test
        @DisplayName("Create a consistency rule successfully")
        void testCreateRule() {
            ConsistencyRuleRequest request = buildRequest("create");
            ConsistencyRuleResponse response = consistencyRuleService.createRule(request);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isNotNull();
            assertThat(response.getPid()).isNotBlank();
            assertThat(response.getCode()).isEqualTo(request.getCode());
            assertThat(response.getName()).isEqualTo(request.getName());
            assertThat(response.getSourceModel()).isEqualTo("shipment_line");
            assertThat(response.getTargetModel()).isEqualTo("order_line");
            assertThat(response.getAggregation()).isEqualTo("sum");
            assertThat(response.getOperator()).isEqualTo("LE");
            assertThat(response.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("Create rule with duplicate code should fail")
        void testCreateDuplicateCode() {
            ConsistencyRuleRequest request = buildRequest("dup");
            consistencyRuleService.createRule(request);

            assertThatThrownBy(() -> consistencyRuleService.createRule(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("Get rule by ID")
        void testGetRuleById() {
            ConsistencyRuleRequest request = buildRequest("get");
            ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

            ConsistencyRuleResponse found = consistencyRuleService.getRuleById(created.getId());
            assertThat(found).isNotNull();
            assertThat(found.getCode()).isEqualTo(created.getCode());
            assertThat(found.getSeverity()).isEqualTo("error");
        }

        @Test
        @DisplayName("Get non-existent rule returns null")
        void testGetNonExistentRule() {
            ConsistencyRuleResponse found = consistencyRuleService.getRuleById(999999999L);
            assertThat(found).isNull();
        }

        @Test
        @DisplayName("Update rule")
        void testUpdateRule() {
            ConsistencyRuleRequest request = buildRequest("update");
            ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

            request.setName("Updated Name");
            request.setSeverity("warning");
            request.setOperator("EQ");
            ConsistencyRuleResponse updated = consistencyRuleService.updateRule(created.getId(), request);

            assertThat(updated.getName()).isEqualTo("Updated Name");
            assertThat(updated.getSeverity()).isEqualTo("warning");
            assertThat(updated.getOperator()).isEqualTo("EQ");
        }

        @Test
        @DisplayName("Update non-existent rule should fail")
        void testUpdateNonExistentRule() {
            ConsistencyRuleRequest request = buildRequest("missing");
            assertThatThrownBy(() -> consistencyRuleService.updateRule(999999999L, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Delete rule")
        void testDeleteRule() {
            ConsistencyRuleRequest request = buildRequest("delete");
            ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

            boolean deleted = consistencyRuleService.deleteRule(created.getId());
            assertThat(deleted).isTrue();

            // Should not be found after deletion
            ConsistencyRuleResponse found = consistencyRuleService.getRuleById(created.getId());
            assertThat(found).isNull();
        }

        @Test
        @DisplayName("Delete non-existent rule returns false")
        void testDeleteNonExistentRule() {
            boolean deleted = consistencyRuleService.deleteRule(999999999L);
            assertThat(deleted).isFalse();
        }

        @Test
        @DisplayName("List rules with pagination")
        void testListRules() {
            // Create multiple rules
            for (int i = 0; i < 3; i++) {
                consistencyRuleService.createRule(buildRequest("list_" + i));
            }

            PaginationResult<ConsistencyRuleResponse> result = consistencyRuleService.listRules(null, 1, 10);
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("List rules filtered by source model")
        void testListRulesFilterBySourceModel() {
            ConsistencyRuleRequest request = buildRequest("filter");
            request.setSourceModel("unique_model_" + System.currentTimeMillis());
            consistencyRuleService.createRule(request);

            PaginationResult<ConsistencyRuleResponse> result =
                    consistencyRuleService.listRules(request.getSourceModel(), 1, 10);
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getSourceModel()).isEqualTo(request.getSourceModel());
        }
    }

    @Nested
    @DisplayName("Validation Logic")
    class ValidationTests {

        @Test
        @DisplayName("Validate returns empty list when no rules exist for model")
        void testValidateNoRules() {
            List<ConsistencyViolation> violations =
                    consistencyRuleService.validate("nonexistent_model", "some_pid");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Batch validate returns empty list when no rules exist")
        void testBatchValidateNoRules() {
            List<ConsistencyViolation> violations =
                    consistencyRuleService.validateBatch("nonexistent_model", List.of("pid1", "pid2"));
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("validateAndThrow does nothing when no rules exist")
        void testValidateAndThrowNoRules() {
            // Should not throw
            assertThatCode(() ->
                    consistencyRuleService.validateAndThrow("nonexistent_model", "some_pid", getTestTenant().getId())
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateForPipeline returns empty when no rules exist")
        void testValidateForPipelineNoRules() {
            List<ConsistencyViolation> violations = consistencyRuleService.validateForPipeline(
                    "nonexistent_model",
                    java.util.Map.of("field1", "value1"),
                    java.util.Map.of(),
                    getTestTenant().getId());
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Rule with WARNING severity does not block validateAndThrow")
        void testWarningSeverityDoesNotBlock() {
            // Create a WARNING rule (even if evaluation fails, it should not throw)
            ConsistencyRuleRequest request = buildRequest("warning");
            request.setSeverity("warning");
            consistencyRuleService.createRule(request);

            // validateAndThrow should not throw for WARNING-only violations
            assertThatCode(() ->
                    consistencyRuleService.validateAndThrow("shipment_line", "some_pid", getTestTenant().getId())
            ).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Rule Identifier Validation")
    class IdentifierValidationTests {

        @Test
        @DisplayName("Create rule with SQL injection in source field should fail validation at evaluation time")
        void testSqlInjectionInSourceField() {
            ConsistencyRuleRequest request = buildRequest("sqli");
            request.setSourceField("quantity; DROP TABLE--");

            // Creation succeeds (string validation is at evaluation time)
            // But trying to validate should fail safely
            ConsistencyRuleResponse created = consistencyRuleService.createRule(request);
            assertThat(created).isNotNull();

            // Evaluation should fail gracefully (logged, not crash)
            List<ConsistencyViolation> violations =
                    consistencyRuleService.validate(request.getSourceModel(), "test_pid");
            // Should return empty (rule evaluation fails gracefully)
            assertThat(violations).isEmpty();
        }
    }
}
