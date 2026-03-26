package com.auraboot.framework.consistency.service;

import com.auraboot.framework.consistency.dao.mapper.ConsistencyRuleMapper;
import com.auraboot.framework.consistency.dto.ConsistencyRuleRequest;
import com.auraboot.framework.consistency.dto.ConsistencyRuleResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PaginationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ConsistencyRuleService.
 * Uses real PostgreSQL via BaseIntegrationTest.
 */
class ConsistencyRuleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConsistencyRuleService consistencyRuleService;

    @Autowired
    private ConsistencyRuleMapper consistencyRuleMapper;

    private final String uniquePrefix = "test_" + System.currentTimeMillis() + "_";

    @Test
    @DisplayName("Should create a consistency rule successfully")
    void testCreateRule() {
        ConsistencyRuleRequest request = buildRequest(uniquePrefix + "shipment_qty_check");

        ConsistencyRuleResponse response = consistencyRuleService.createRule(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getPid()).isNotNull();
        assertThat(response.getCode()).isEqualTo(uniquePrefix + "shipment_qty_check");
        assertThat(response.getSourceModel()).isEqualTo("shipment_line");
        assertThat(response.getTargetModel()).isEqualTo("order_line");
        assertThat(response.getAggregation()).isEqualTo("sum");
        assertThat(response.getOperator()).isEqualTo("LE");
        assertThat(response.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should reject duplicate rule code within same tenant")
    void testCreateDuplicateRuleCode() {
        String code = uniquePrefix + "dup_rule";
        ConsistencyRuleRequest request = buildRequest(code);
        consistencyRuleService.createRule(request);

        ConsistencyRuleRequest duplicateRequest = buildRequest(code);
        assertThatThrownBy(() -> consistencyRuleService.createRule(duplicateRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Should list rules with pagination")
    void testListRules() {
        for (int i = 0; i < 3; i++) {
            ConsistencyRuleRequest request = buildRequest(uniquePrefix + "list_rule_" + i);
            consistencyRuleService.createRule(request);
        }

        PaginationResult<ConsistencyRuleResponse> result = consistencyRuleService.listRules(null, 1, 10);
        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(3);
        assertThat(result.getRecords()).isNotEmpty();
    }

    @Test
    @DisplayName("Should filter rules by source model")
    void testListRulesBySourceModel() {
        ConsistencyRuleRequest request1 = buildRequest(uniquePrefix + "filter_a");
        request1.setSourceModel("model_a_" + uniquePrefix);
        consistencyRuleService.createRule(request1);

        ConsistencyRuleRequest request2 = buildRequest(uniquePrefix + "filter_b");
        request2.setSourceModel("model_b_" + uniquePrefix);
        consistencyRuleService.createRule(request2);

        PaginationResult<ConsistencyRuleResponse> result =
                consistencyRuleService.listRules("model_a_" + uniquePrefix, 1, 10);
        assertThat(result.getRecords())
                .allMatch(r -> ("model_a_" + uniquePrefix).equals(r.getSourceModel()));
    }

    @Test
    @DisplayName("Should get rule by ID")
    void testGetRuleById() {
        ConsistencyRuleRequest request = buildRequest(uniquePrefix + "get_by_id");
        ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

        ConsistencyRuleResponse found = consistencyRuleService.getRuleById(created.getId());
        assertThat(found).isNotNull();
        assertThat(found.getCode()).isEqualTo(uniquePrefix + "get_by_id");
    }

    @Test
    @DisplayName("Should return null for non-existent rule ID")
    void testGetRuleByIdNotFound() {
        ConsistencyRuleResponse found = consistencyRuleService.getRuleById(999999999L);
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("Should update a consistency rule")
    void testUpdateRule() {
        ConsistencyRuleRequest request = buildRequest(uniquePrefix + "update_rule");
        ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

        request.setName("Updated Rule Name");
        request.setOperator("LT");
        request.setSeverity("warning");
        ConsistencyRuleResponse updated = consistencyRuleService.updateRule(created.getId(), request);

        assertThat(updated.getName()).isEqualTo("Updated Rule Name");
        assertThat(updated.getOperator()).isEqualTo("LT");
        assertThat(updated.getSeverity()).isEqualTo("warning");
    }

    @Test
    @DisplayName("Should throw when updating non-existent rule")
    void testUpdateNonExistentRule() {
        ConsistencyRuleRequest request = buildRequest(uniquePrefix + "nonexist");
        assertThatThrownBy(() -> consistencyRuleService.updateRule(999999999L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Should soft-delete a consistency rule")
    void testDeleteRule() {
        ConsistencyRuleRequest request = buildRequest(uniquePrefix + "delete_rule");
        ConsistencyRuleResponse created = consistencyRuleService.createRule(request);

        boolean deleted = consistencyRuleService.deleteRule(created.getId());
        assertThat(deleted).isTrue();

        // Should not be found anymore
        ConsistencyRuleResponse found = consistencyRuleService.getRuleById(created.getId());
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("Should return false when deleting non-existent rule")
    void testDeleteNonExistentRule() {
        boolean deleted = consistencyRuleService.deleteRule(999999999L);
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("Should return empty violations when no rules exist for model")
    void testValidateNoRules() {
        var violations = consistencyRuleService.validate("nonexistent_model_" + uniquePrefix, "some_record_id");
        assertThat(violations).isEmpty();
    }

    private ConsistencyRuleRequest buildRequest(String code) {
        ConsistencyRuleRequest request = new ConsistencyRuleRequest();
        request.setCode(code);
        request.setName("Test Rule: " + code);
        request.setRuleType("cross_document");
        request.setSeverity("error");
        request.setSourceModel("shipment_line");
        request.setSourceField("quantity");
        request.setTargetModel("order_line");
        request.setTargetField("quantity");
        request.setLinkField("order_line_id");
        request.setAggregation("sum");
        request.setOperator("LE");
        request.setMessageTemplate("Shipped qty ({sourceSum}) exceeds order qty ({targetValue})");
        request.setEnabled(true);
        return request;
    }
}
