package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.StateGraphDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StateGraph Integration Test
 *
 * Covers P2-2 requirements:
 * 1. StateGraphDefinition CRUD
 * 2. State node definition (INITIAL/NORMAL/TERMINAL)
 * 3. State transitions with guards
 * 4. Graph publishing and versioning
 * 5. Graph visualization data
 * 6. Transition validation via StateTransitionEngine
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("StateGraph Integration Test - P2-2")
class StateGraphIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private StateGraphService stateGraphService;

    @Autowired
    private StateTransitionEngine stateTransitionEngine;

    /**
     * Helper method to create a state graph for testing
     */
    private StateGraphDefinition createTestStateGraph(String suffix) {
        String code = "sg_test_" + System.currentTimeMillis() + "_" + suffix;
        String modelCode = "test_model_" + suffix;
        
        StateGraphCreateRequest request = new StateGraphCreateRequest();
        request.setCode(code);
        request.setDisplayName("Test Graph " + suffix);
        request.setDescription("State graph for testing");
        request.setModelCode(modelCode);
        request.setStateField("status");

        // Define nodes
        List<StateNodeDTO> nodes = List.of(
                StateNodeDTO.builder()
                        .code("pending")
                        .displayName("Pending")
                        .type("initial")
                        .build(),
                StateNodeDTO.builder()
                        .code("approved")
                        .displayName("Approved")
                        .type("normal")
                        .build(),
                StateNodeDTO.builder()
                        .code("completed")
                        .displayName("Completed")
                        .type("terminal")
                        .build()
        );
        request.setNodes(nodes);

        // Define transitions
        List<StateTransitionDTO> transitions = List.of(
                StateTransitionDTO.builder()
                        .from("pending")
                        .to("approved")
                        .triggerCommand("approve")
                        .displayName("Approve")
                        .build(),
                StateTransitionDTO.builder()
                        .from("approved")
                        .to("completed")
                        .triggerCommand("complete")
                        .displayName("Complete")
                        .build()
        );
        request.setTransitions(transitions);

        return stateGraphService.create(request);
    }

    // ==================== CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("P2-2.1: Create state graph definition")
    void test01_createStateGraph() {
        String code = "sg_create_" + System.currentTimeMillis();
        String modelCode = "test_order_model";
        
        StateGraphCreateRequest request = new StateGraphCreateRequest();
        request.setCode(code);
        request.setDisplayName("Order Lifecycle");
        request.setDescription("State graph for order processing");
        request.setModelCode(modelCode);
        request.setStateField("status");

        // Define nodes
        List<StateNodeDTO> nodes = List.of(
                StateNodeDTO.builder()
                        .code("pending")
                        .displayName("Pending")
                        .type("initial")
                        .description("Order created, awaiting approval")
                        .build(),
                StateNodeDTO.builder()
                        .code("approved")
                        .displayName("Approved")
                        .type("normal")
                        .description("Order approved")
                        .build(),
                StateNodeDTO.builder()
                        .code("completed")
                        .displayName("Completed")
                        .type("terminal")
                        .description("Order completed")
                        .build()
        );
        request.setNodes(nodes);

        // Define transitions
        List<StateTransitionDTO> transitions = List.of(
                StateTransitionDTO.builder()
                        .from("pending")
                        .to("approved")
                        .triggerCommand("approve_order")
                        .displayName("Approve")
                        .build(),
                StateTransitionDTO.builder()
                        .from("approved")
                        .to("completed")
                        .triggerCommand("complete_order")
                        .displayName("Complete")
                        .build()
        );
        request.setTransitions(transitions);

        StateGraphDefinition result = stateGraphService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertEquals(code, result.getCode());
        assertEquals(modelCode, result.getModelCode());
        assertEquals("status", result.getStateField());

        log.info("Created state graph: pid={}, code={}", result.getPid(), code);
    }

    @Test
    @Order(2)
    @DisplayName("P2-2.1: Get state graph by PID")
    void test02_getByPid() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("getByPid");
        assertNotNull(created.getPid());

        StateGraphDefinition result = stateGraphService.getByPid(created.getPid());

        assertNotNull(result);
        assertEquals(created.getPid(), result.getPid());
        assertEquals(created.getCode(), result.getCode());
    }

    @Test
    @Order(3)
    @DisplayName("P2-2.1: Get state graph by code")
    void test03_getCurrentByCode() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("getByCode");

        StateGraphDefinition result = stateGraphService.getCurrentByCode(created.getCode());

        assertNotNull(result);
        assertEquals(created.getCode(), result.getCode());
    }

    @Test
    @Order(4)
    @DisplayName("P2-2.1: List state graphs by model code")
    void test04_listByModelCode() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("listByModel");

        List<StateGraphDefinition> graphs = stateGraphService.listByModelCode(created.getModelCode());

        assertNotNull(graphs);
        assertTrue(graphs.stream().anyMatch(g -> created.getCode().equals(g.getCode())));
    }

    @Test
    @Order(5)
    @DisplayName("P2-2.1: Update state graph definition")
    void test05_updateStateGraph() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("update");
        assertNotNull(created.getPid());

        StateGraphCreateRequest request = new StateGraphCreateRequest();
        request.setCode(created.getCode());
        request.setDisplayName("Updated Graph");
        request.setDescription("Updated description");
        request.setModelCode(created.getModelCode());
        request.setStateField("status");
        request.setNodes(List.of(
                StateNodeDTO.builder().code("pending").displayName("Pending").type("initial").build(),
                StateNodeDTO.builder().code("done").displayName("Done").type("terminal").build()
        ));
        request.setTransitions(List.of(
                StateTransitionDTO.builder().from("pending").to("done").triggerCommand("finish").build()
        ));

        StateGraphDefinition result = stateGraphService.update(created.getPid(), request);

        assertNotNull(result);
        assertEquals("Updated Graph", result.getDisplayName());
    }

    // ==================== Publish Tests ====================

    @Test
    @Order(10)
    @DisplayName("P2-2.2: Publish state graph")
    void test10_publish() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("publish");
        assertNotNull(created.getPid());

        assertDoesNotThrow(() -> {
            stateGraphService.publish(created.getPid());
        });

        StateGraphDefinition published = stateGraphService.getByPid(created.getPid());
        assertNotNull(published);
        log.info("Published state graph: pid={}", created.getPid());
    }

    // ==================== Visualization Tests ====================

    @Test
    @Order(20)
    @DisplayName("P2-2.3: Get graph visualization data")
    void test20_getGraphVisualization() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("viz");

        Map<String, Object> viz = stateGraphService.getGraphVisualization(created.getCode());

        assertNotNull(viz);
        assertTrue(viz.containsKey("nodes") || viz.containsKey("edges"),
                "Visualization should contain nodes and/or edges");
        log.info("Visualization data: {}", viz);
    }

    // ==================== Transition From State Tests ====================

    @Test
    @Order(30)
    @DisplayName("P2-2.4: Get transitions from PENDING state")
    void test30_getTransitionsFromState() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("transitions");

        List<StateTransitionDTO> transitions = stateGraphService.getTransitionsFromState(created.getCode(), "pending");

        assertNotNull(transitions);
        assertFalse(transitions.isEmpty(), "PENDING should have outgoing transitions");
        assertTrue(transitions.stream().allMatch(t -> "pending".equals(t.getFrom())));
    }

    @Test
    @Order(31)
    @DisplayName("P2-2.4: Get transitions from TERMINAL state returns empty")
    void test31_getTransitionsFromTerminal() {
        // Create a graph first
        StateGraphDefinition created = createTestStateGraph("terminal");

        List<StateTransitionDTO> transitions = stateGraphService.getTransitionsFromState(created.getCode(), "completed");

        assertNotNull(transitions);
        assertTrue(transitions.isEmpty(), "TERMINAL state should have no outgoing transitions");
    }

    // ==================== StateTransitionEngine Tests ====================

    @Test
    @Order(40)
    @DisplayName("P2-2.5: Validate allowed transition")
    void test40_validateTransition_allowed() {
        // Create and publish a graph first
        StateGraphDefinition created = createTestStateGraph("validate");
        stateGraphService.publish(created.getPid());
        
        Long tenantId = getTestTenant().getId();

        assertDoesNotThrow(() -> {
            stateTransitionEngine.validateTransition(
                    tenantId, created.getModelCode(), "status",
                    "pending", "approve", Map.of());
        });
    }

    @Test
    @Order(41)
    @DisplayName("P2-2.5: Validate disallowed transition throws")
    void test41_validateTransition_disallowed() {
        // Create and publish a graph first
        StateGraphDefinition created = createTestStateGraph("disallowed");
        stateGraphService.publish(created.getPid());
        
        Long tenantId = getTestTenant().getId();

        // PENDING -> COMPLETED is not a valid direct transition
        assertThrows(Exception.class, () -> {
            stateTransitionEngine.validateTransition(
                    tenantId, created.getModelCode(), "status",
                    "pending", "complete", Map.of());
        });
    }

    @Test
    @Order(42)
    @DisplayName("P2-2.5: Resolve target state for command")
    void test42_resolveTargetState() {
        // Create and publish a graph first
        StateGraphDefinition created = createTestStateGraph("resolve");
        stateGraphService.publish(created.getPid());
        
        Long tenantId = getTestTenant().getId();

        String targetState = stateTransitionEngine.resolveTargetState(
                tenantId, created.getModelCode(), "pending", "approve");

        assertEquals("approved", targetState);
    }

    @Test
    @Order(43)
    @DisplayName("P2-2.5: Model without state graph passes silently")
    void test43_validateTransition_noGraph() {
        Long tenantId = getTestTenant().getId();
        
        // Model without state graph should pass validation silently
        assertDoesNotThrow(() -> {
            stateTransitionEngine.validateTransition(
                    tenantId, "model_without_graph_" + System.currentTimeMillis(), "status",
                    "any", "any_cmd", Map.of());
        });
    }

    // ==================== Delete Tests ====================

    @Test
    @Order(90)
    @DisplayName("P2-2.1: Delete state graph")
    void test90_deleteStateGraph() {
        // Create a graph specifically to delete
        String deleteCode = "sg_delete_" + System.currentTimeMillis();
        StateGraphCreateRequest request = new StateGraphCreateRequest();
        request.setCode(deleteCode);
        request.setModelCode("delete_model");
        request.setStateField("status");
        request.setNodes(List.of(
                StateNodeDTO.builder().code("start").type("initial").build(),
                StateNodeDTO.builder().code("end").type("terminal").build()
        ));
        request.setTransitions(List.of(
                StateTransitionDTO.builder().from("start").to("end").triggerCommand("finish").build()
        ));

        StateGraphDefinition created = stateGraphService.create(request);
        assertNotNull(created.getPid());

        assertDoesNotThrow(() -> {
            stateGraphService.delete(created.getPid());
        });
    }
}
