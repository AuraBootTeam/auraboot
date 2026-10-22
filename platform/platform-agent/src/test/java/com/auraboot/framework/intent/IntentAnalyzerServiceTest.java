package com.auraboot.framework.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.intent.dto.IntentAnalysisResult;
import com.auraboot.framework.intent.service.IntentAnalyzerService;
import com.auraboot.framework.intent.service.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IntentAnalyzerService.
 * LLM is mocked to return controlled JSON.
 */
class IntentAnalyzerServiceTest {

    private IntentAnalyzerService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Mock LLM client that returns a known JSON response
        LlmClient mockLlm = prompt -> MOCK_LLM_RESPONSE;
        service = new IntentAnalyzerService(objectMapper, mockLlm);
    }

    @Test
    void analyze_shouldExtractEntitiesFromLlmResponse() {
        IntentAnalysisResult result = service.analyze("Build an order management system", "text");

        assertThat(result).isNotNull();
        assertThat(result.getSummary()).contains("order management");
        assertThat(result.getEntities()).hasSize(2);

        // Verify first entity
        var order = result.getEntities().get(0);
        assertThat(order.getCode()).isEqualTo("order");
        assertThat(order.getName()).isEqualTo("Order");
        assertThat(order.getFields()).hasSize(3);

        // Verify fields
        var nameField = order.getFields().get(0);
        assertThat(nameField.getCode()).isEqualTo("ord_name");
        assertThat(nameField.getType()).isEqualTo("string");
        assertThat(nameField.isRequired()).isTrue();

        // Verify second entity
        var orderItem = result.getEntities().get(1);
        assertThat(orderItem.getCode()).isEqualTo("order_item");
    }

    @Test
    void analyze_shouldExtractRelationships() {
        IntentAnalysisResult result = service.analyze("Build an order management system", "text");

        assertThat(result.getRelationships()).hasSize(1);
        var rel = result.getRelationships().get(0);
        assertThat(rel.getFromEntity()).isEqualTo("order");
        assertThat(rel.getToEntity()).isEqualTo("order_item");
        assertThat(rel.getType()).isEqualTo("one_to_many");
    }

    @Test
    void analyze_shouldExtractStateMachines() {
        IntentAnalysisResult result = service.analyze("Build an order management system", "text");

        assertThat(result.getStateMachines()).hasSize(1);
        var sm = result.getStateMachines().get(0);
        assertThat(sm.getEntityCode()).isEqualTo("order");
        assertThat(sm.getStates()).containsExactly("draft", "confirmed", "shipped", "completed");
        assertThat(sm.getTransitions()).hasSize(3);
    }

    @Test
    void analyze_shouldExtractBusinessRules() {
        IntentAnalysisResult result = service.analyze("Build an order management system", "text");

        assertThat(result.getRules()).hasSize(1);
        var rule = result.getRules().get(0);
        assertThat(rule.getEntityCode()).isEqualTo("order_item");
        assertThat(rule.getRuleType()).isEqualTo("validation");
    }

    @Test
    void analyze_shouldHandleMarkdownCodeFences() {
        LlmClient wrappedLlm = prompt -> "```json\n" + MOCK_LLM_RESPONSE + "\n```";
        IntentAnalyzerService svcWithFences = new IntentAnalyzerService(new ObjectMapper(), wrappedLlm);

        IntentAnalysisResult result = svcWithFences.analyze("requirement doc", "markdown");
        assertThat(result.getEntities()).hasSize(2);
    }

    @Test
    void analyze_shouldRejectEmptyContent() {
        assertThatThrownBy(() -> service.analyze("", "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void analyze_shouldRejectNullContent() {
        assertThatThrownBy(() -> service.analyze(null, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Mock data ----

    private static final String MOCK_LLM_RESPONSE = """
            {
              "summary": "An order management system with orders and order items",
              "entities": [
                {
                  "code": "order",
                  "name": "Order",
                  "description": "A customer purchase order",
                  "fields": [
                    { "code": "ord_name", "name": "Order Name", "type": "string", "required": true, "description": "Name of the order" },
                    { "code": "ord_total", "name": "Total Amount", "type": "decimal", "required": true, "description": "Total order amount" },
                    { "code": "ord_status", "name": "Status", "type": "enum", "required": true, "description": "Order status", "enumValues": "DRAFT,CONFIRMED,SHIPPED,COMPLETED" }
                  ]
                },
                {
                  "code": "order_item",
                  "name": "Order Item",
                  "description": "A line item within an order",
                  "fields": [
                    { "code": "oi_product_name", "name": "Product Name", "type": "string", "required": true, "description": "Product name" },
                    { "code": "oi_quantity", "name": "Quantity", "type": "integer", "required": true, "description": "Quantity ordered" },
                    { "code": "oi_price", "name": "Unit Price", "type": "decimal", "required": true, "description": "Price per unit" },
                    { "code": "oi_order_id", "name": "Order", "type": "reference", "required": true, "description": "Parent order", "referenceModel": "order" }
                  ]
                }
              ],
              "relationships": [
                { "fromEntity": "order", "toEntity": "order_item", "type": "one_to_many", "foreignKey": "oi_order_id", "description": "An order has many items" }
              ],
              "stateMachines": [
                {
                  "entityCode": "order",
                  "fieldCode": "ord_status",
                  "states": ["draft", "confirmed", "shipped", "completed"],
                  "transitions": [
                    { "from": "draft", "to": "confirmed", "action": "Confirm", "description": "Confirm the order" },
                    { "from": "confirmed", "to": "shipped", "action": "Ship", "description": "Ship the order" },
                    { "from": "shipped", "to": "completed", "action": "Complete", "description": "Mark as completed" }
                  ]
                }
              ],
              "rules": [
                { "entityCode": "order_item", "ruleType": "validation", "expression": "oi_quantity > 0", "description": "Quantity must be positive" }
              ]
            }
            """;
}
