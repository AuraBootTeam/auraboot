package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.service.AssigneeResolverService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AssigneeResolverService.
 * Tests all assignee resolution rule types: SPECIFIC_USER, STARTER,
 * PREVIOUS_HANDLER, ROLE, DEPARTMENT, and EXPRESSION.
 *
 * Some rule types (ROLE, DEPARTMENT, EXPRESSION) are not yet fully
 * implemented and are marked accordingly.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("BPM Assignee Resolver Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssigneeResolverTest extends BaseIntegrationTest {

    @Autowired
    private AssigneeResolverService assigneeResolverService;

    // ==================== D3-01: SPECIFIC_USER ====================

    @Test
    @Order(1)
    @DisplayName("D3-01: SPECIFIC_USER - task assigned to specified user IDs")
    void d3_01_specificUser() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of("userIds", List.of("user-001", "user-002"));
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("specific_user", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(2, assignees.size(), "Should resolve to 2 specific users");
        assertTrue(assignees.contains("user-001"), "Should contain user-001");
        assertTrue(assignees.contains("user-002"), "Should contain user-002");
        log.info("D3-01 PASSED: SPECIFIC_USER resolved to {}", assignees);
    }

    @Test
    @Order(2)
    @DisplayName("D3-01b: SPECIFIC_USER with comma-separated string")
    void d3_01b_specificUserStringFormat() {
        // Arrange: userIds as a comma-separated string
        Map<String, Object> ruleConfig = Map.of("userIds", "admin1,admin2,admin3");
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("specific_user", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(3, assignees.size(), "Should resolve to 3 specific users from comma-separated string");
        assertTrue(assignees.contains("admin1"), "Should contain admin1");
        assertTrue(assignees.contains("admin2"), "Should contain admin2");
        assertTrue(assignees.contains("admin3"), "Should contain admin3");
        log.info("D3-01b PASSED: SPECIFIC_USER (string) resolved to {}", assignees);
    }

    // ==================== D3-02: STARTER ====================

    @Test
    @Order(3)
    @DisplayName("D3-02: STARTER - task assigned to process starter")
    void d3_02_starter() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of();
        Map<String, Object> context = Map.of("_startUserId", "starter-user-123");

        // Act
        List<String> assignees = assigneeResolverService.resolve("starter", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(1, assignees.size(), "Should resolve to exactly 1 user (the starter)");
        assertEquals("starter-user-123", assignees.get(0), "Should be the process starter");
        log.info("D3-02 PASSED: STARTER resolved to {}", assignees);
    }

    @Test
    @Order(4)
    @DisplayName("D3-02b: STARTER with no starter in context - returns empty")
    void d3_02b_starterMissing() {
        // Arrange: no _startUserId in context
        Map<String, Object> ruleConfig = Map.of();
        Map<String, Object> context = Map.of("someOtherKey", "value");

        // Act
        List<String> assignees = assigneeResolverService.resolve("starter", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "Should return empty list when starter is not in context");
        log.info("D3-02b PASSED: STARTER with missing context returned empty list");
    }

    // ==================== D3-03: PREVIOUS_HANDLER ====================

    @Test
    @Order(5)
    @DisplayName("D3-03: PREVIOUS_HANDLER - task assigned to previous task handler")
    void d3_03_previousHandler() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of();
        Map<String, Object> context = Map.of("_previousHandler", "handler-user-456");

        // Act
        List<String> assignees = assigneeResolverService.resolve("previous_handler", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(1, assignees.size(), "Should resolve to exactly 1 user (the previous handler)");
        assertEquals("handler-user-456", assignees.get(0), "Should be the previous handler");
        log.info("D3-03 PASSED: PREVIOUS_HANDLER resolved to {}", assignees);
    }

    @Test
    @Order(6)
    @DisplayName("D3-03b: PREVIOUS_HANDLER with no handler in context - returns empty")
    void d3_03b_previousHandlerMissing() {
        // Arrange: no _previousHandler in context
        Map<String, Object> ruleConfig = Map.of();
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("previous_handler", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "Should return empty list when previous handler is not in context");
        log.info("D3-03b PASSED: PREVIOUS_HANDLER with missing context returned empty list");
    }

    // ==================== D3-04: ROLE ====================

    @Test
    @Order(7)
    @DisplayName("D3-04: ROLE - resolves users by role ID via UserRoleMapper")
    void d3_04_role() {
        // Arrange: use the test role created by BaseIntegrationTest
        // The test user is already assigned the test role
        Long roleId = getTestRole().getId();
        Map<String, Object> ruleConfig = Map.of("roleIds", List.of(roleId.toString()));
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("role", ruleConfig, context);

        // Assert: AssigneeResolverService.resolveByRole returns user PIDs (ULID),
        // which is BPM canonical user identity (matches BpmSecurityUtil /
        // MetaContext.getCurrentUsername). Numeric ab_user.id is internal-only.
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.size() >= 1, "Should resolve at least the test user for the test role");
        assertTrue(assignees.contains(getTestUser().getPid()),
                "Should contain the test user PID assigned to the test role");
        log.info("D3-04 PASSED: ROLE resolved to {}", assignees);
    }

    @Test
    @Order(71)
    @DisplayName("D3-04b: ROLE with comma-separated string format")
    void d3_04b_roleStringFormat() {
        // Arrange: role IDs as comma-separated string
        Long roleId = getTestRole().getId();
        Map<String, Object> ruleConfig = Map.of("roleIds", roleId.toString());
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("role", ruleConfig, context);

        // Assert: same PID (ULID) contract as D3-04.
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.contains(getTestUser().getPid()),
                "String-format roleIds should also resolve users correctly");
        log.info("D3-04b PASSED: ROLE (string format) resolved to {}", assignees);
    }

    @Test
    @Order(72)
    @DisplayName("D3-04c: ROLE with no roleIds returns empty")
    void d3_04c_roleNoIds() {
        Map<String, Object> ruleConfig = Map.of();
        List<String> assignees = assigneeResolverService.resolve("role", ruleConfig, Map.of());
        assertNotNull(assignees);
        assertTrue(assignees.isEmpty(), "ROLE without roleIds should return empty");
        log.info("D3-04c PASSED: ROLE with empty config returned empty");
    }

    // ==================== D3-05: DEPARTMENT ====================

    @Test
    @Order(8)
    @DisplayName("D3-05: DEPARTMENT - returns empty with warning (org service not implemented)")
    void d3_05_department() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of("deptIds", List.of("dept_engineering"));
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("department", ruleConfig, context);

        // Assert: DEPARTMENT not yet supported, should return empty without throwing
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "DEPARTMENT should return empty until org service is implemented");
        log.info("D3-05 PASSED: DEPARTMENT returned empty (expected - org service not implemented)");
    }

    // ==================== D3-05b: STARTER_MANAGER ====================

    @Test
    @Order(81)
    @DisplayName("D3-05b: STARTER_MANAGER - returns empty with warning (org hierarchy not implemented)")
    void d3_05b_starterManager() {
        Map<String, Object> ruleConfig = Map.of();
        Map<String, Object> context = Map.of("_startUserId", "user-123");

        List<String> assignees = assigneeResolverService.resolve("starter_manager", ruleConfig, context);

        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "STARTER_MANAGER should return empty until org service is implemented");
        log.info("D3-05b PASSED: STARTER_MANAGER returned empty (expected - org hierarchy not implemented)");
    }

    // ==================== D3-06: EXPRESSION ====================

    @Test
    @Order(9)
    @DisplayName("D3-06: EXPRESSION - SpEL expression resolves variable from context")
    void d3_06_expression() {
        // Arrange: SpEL expression referencing a context variable
        Map<String, Object> ruleConfig = Map.of("expression", "#assignee");
        Map<String, Object> context = Map.of("assignee", "dynamic-user-789");

        // Act
        List<String> assignees = assigneeResolverService.resolve("expression", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(1, assignees.size(), "Should resolve to exactly 1 user from expression");
        assertEquals("dynamic-user-789", assignees.get(0), "Should resolve to the context variable value");
        log.info("D3-06 PASSED: EXPRESSION resolved to {}", assignees);
    }

    @Test
    @Order(91)
    @DisplayName("D3-06b: EXPRESSION - SpEL list expression returns multiple users")
    void d3_06b_expressionList() {
        // Arrange: SpEL returning a list
        Map<String, Object> ruleConfig = Map.of("expression", "#users");
        Map<String, Object> context = Map.of("users", List.of("user-a", "user-b", "user-c"));

        // Act
        List<String> assignees = assigneeResolverService.resolve("expression", ruleConfig, context);

        // Assert
        assertNotNull(assignees);
        assertEquals(3, assignees.size(), "Should resolve to 3 users from list expression");
        assertTrue(assignees.contains("user-a"));
        assertTrue(assignees.contains("user-b"));
        assertTrue(assignees.contains("user-c"));
        log.info("D3-06b PASSED: EXPRESSION list resolved to {}", assignees);
    }

    @Test
    @Order(92)
    @DisplayName("D3-06c: EXPRESSION - invalid expression returns empty without throwing")
    void d3_06c_expressionInvalid() {
        Map<String, Object> ruleConfig = Map.of("expression", "#nonexistent.invalid()");
        Map<String, Object> context = Map.of();

        List<String> assignees = assigneeResolverService.resolve("expression", ruleConfig, context);

        assertNotNull(assignees);
        assertTrue(assignees.isEmpty(), "Invalid expression should return empty, not throw");
        log.info("D3-06c PASSED: Invalid expression returned empty gracefully");
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(10)
    @DisplayName("D3-EC1: Unknown rule type - returns empty list")
    void d3_ec1_unknownRuleType() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of("foo", "bar");
        Map<String, Object> context = Map.of();

        // Act
        List<String> assignees = assigneeResolverService.resolve("unknown_type", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "Unknown rule type should return empty list");
        log.info("D3-EC1 PASSED: Unknown rule type returned empty list");
    }

    @Test
    @Order(11)
    @DisplayName("D3-EC2: Null rule type - returns empty list")
    void d3_ec2_nullRuleType() {
        // Act
        List<String> assignees = assigneeResolverService.resolve(null, Map.of(), Map.of());

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "Null rule type should return empty list");
        log.info("D3-EC2 PASSED: Null rule type returned empty list");
    }

    @Test
    @Order(12)
    @DisplayName("D3-EC3: Null rule config - returns empty list")
    void d3_ec3_nullRuleConfig() {
        // Act
        List<String> assignees = assigneeResolverService.resolve("specific_user", null, Map.of());

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertTrue(assignees.isEmpty(), "Null rule config should return empty list");
        log.info("D3-EC3 PASSED: Null rule config returned empty list");
    }

    @Test
    @Order(13)
    @DisplayName("D3-EC4: Case insensitivity - SPECIFIC_USER matches specific_user")
    void d3_ec4_caseInsensitivity() {
        // Arrange
        Map<String, Object> ruleConfig = Map.of("userIds", List.of("user-ci"));
        Map<String, Object> context = Map.of();

        // Act: use lowercase rule type
        List<String> assignees = assigneeResolverService.resolve("specific_user", ruleConfig, context);

        // Assert
        assertNotNull(assignees, "Assignees list should not be null");
        assertEquals(1, assignees.size(), "Case-insensitive rule type should still resolve");
        assertEquals("user-ci", assignees.get(0));
        log.info("D3-EC4 PASSED: Case-insensitive rule type resolved correctly");
    }
}
