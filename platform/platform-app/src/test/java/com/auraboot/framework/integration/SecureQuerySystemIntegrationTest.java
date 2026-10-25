package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.QueryAuditLog;
import com.auraboot.framework.meta.mapper.QueryAuditLogMapper;
import com.auraboot.framework.meta.security.SqlInjectionProtector;
import com.auraboot.framework.meta.service.QueryAuditService;
import com.auraboot.framework.meta.service.SecureQueryExecutor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

/**
 * Secure Query System Integration Test
 * 
 * Tests SecureQueryExecutor, SqlInjectionProtector, QueryAuditService integration.
 * All tests have real assertions - no skipping when components are missing.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class SecureQuerySystemIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SecureQueryExecutor secureQueryExecutor;

    @Autowired
    private SqlInjectionProtector sqlInjectionProtector;

    @Autowired
    private QueryAuditService queryAuditService;

    @Autowired
    private QueryAuditLogMapper queryAuditLogMapper;

    @Test
    @Order(1)
    @DisplayName("Should load all secure query system components")
    void testComponentsLoaded() {
        // All components must be loaded - no optional injection
        assertThat(secureQueryExecutor).isNotNull();
        assertThat(sqlInjectionProtector).isNotNull();
        assertThat(queryAuditService).isNotNull();
        assertThat(queryAuditLogMapper).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Should validate query conditions - returns result with security assessment")
    void testQueryConditionValidation() {
        QueryCondition condition = QueryCondition.builder()
                .fieldName("name")
                .operator(QueryCondition.Operator.EQ)
                .value("test")
                .build();

        QuerySecurityValidationResult result = sqlInjectionProtector.validateQueryConditions(
                Arrays.asList(condition));

        // Result should not be null and should have a definitive assessment
        assertThat(result).isNotNull();
        assertThat(result.getValid()).isTrue();
        assertThat(result.getRiskLevel()).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("Should detect SQL injection patterns in query value")
    void testSqlInjectionDetection() {
        QueryCondition dangerousCondition = QueryCondition.builder()
                .fieldName("name")
                .operator(QueryCondition.Operator.EQ)
                .value("'; DROP TABLE users; --")
                .build();

        QuerySecurityValidationResult result = sqlInjectionProtector.validateQueryConditions(
                Arrays.asList(dangerousCondition));

        assertThat(result).isNotNull();
        assertThat(result.getValid()).isFalse();
        assertThat(result.getSecurityIssues()).isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("Should log query execution successfully")
    void testQueryExecutionLogging() {
        SecureQueryRequest testRequest = new SecureQueryRequest();
        testRequest.setTenantId(getTestTenant().getId());
        testRequest.setUserId(getTestUser().getId());
        testRequest.setModelCode("test_model");
        testRequest.setQueryType(QueryType.SELECT_ALL);
        testRequest.setQueryId("test-query-" + System.currentTimeMillis());

        // Should not throw exception
        assertThatCode(() -> 
            queryAuditService.logQueryExecution(testRequest, "test result", 100L)
        ).doesNotThrowAnyException();
    }

    @Test
    @Order(5)
    @DisplayName("Should log query error successfully")
    void testQueryErrorLogging() {
        SecureQueryRequest testRequest = new SecureQueryRequest();
        testRequest.setTenantId(getTestTenant().getId());
        testRequest.setUserId(getTestUser().getId());
        testRequest.setModelCode("test_model");
        testRequest.setQueryType(QueryType.SELECT_ALL);
        testRequest.setQueryId("test-error-query-" + System.currentTimeMillis());

        Exception testError = new RuntimeException("Test error");

        // Should not throw exception
        assertThatCode(() -> 
            queryAuditService.logQueryError(testRequest, testError, 50L)
        ).doesNotThrowAnyException();
    }

    @Test
    @Order(6)
    @DisplayName("Should have audit log mapper available")
    void testAuditLogMapperAvailable() {
        // Verify mapper is loaded
        assertThat(queryAuditLogMapper).isNotNull();
    }

    @Test
    @Order(7)
    @DisplayName("Should have query audit service available")
    void testQueryAuditServiceAvailable() {
        // Verify service is loaded
        assertThat(queryAuditService).isNotNull();
    }

    @Test
    @Order(8)
    @DisplayName("Should validate query security - returns result")
    void testQuerySecurityValidation() {
        SecureQueryRequest testRequest = new SecureQueryRequest();
        testRequest.setTenantId(getTestTenant().getId());
        testRequest.setUserId(getTestUser().getId());
        testRequest.setModelCode("test_model");
        testRequest.setQueryType(QueryType.SELECT_ALL);
        testRequest.setQueryId("security-test-" + System.currentTimeMillis());

        QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.EQ)
                .value("active")
                .build();
        testRequest.setConditions(Arrays.asList(condition));

        QuerySecurityValidationResult result = secureQueryExecutor.validateQuerySecurity(testRequest);

        assertThat(result).isNotNull();
        // Result should have a definitive assessment
        assertThat(result.getValid()).isNotNull();
    }

    @Test
    @Order(9)
    @DisplayName("Should check query permissions")
    void testQueryPermissionCheck() {
        SecureQueryRequest testRequest = new SecureQueryRequest();
        testRequest.setTenantId(getTestTenant().getId());
        testRequest.setUserId(getTestUser().getId());
        testRequest.setModelCode("secure_query_test_model");
        testRequest.setQueryType(QueryType.SELECT_ALL);
        testRequest.setQueryId("permission-test-" + System.currentTimeMillis());

        QueryAccessCheckResult result = secureQueryExecutor.checkQueryPermissions(testRequest);

        assertThat(result).isNotNull();
        assertThat(result.getHasPermission()).isFalse();
        assertThat(result.getAccessContext()).isNotNull();
        assertThat(result.getAccessContext()).containsKey("permissionCode");
    }

    @Test
    @Order(10)
    @DisplayName("Should validate query complexity")
    void testQueryComplexityValidation() {
        SecureQueryRequest testRequest = new SecureQueryRequest();
        testRequest.setTenantId(getTestTenant().getId());
        testRequest.setUserId(getTestUser().getId());
        testRequest.setModelCode("test_model");
        testRequest.setQueryType(QueryType.SELECT_ALL);
        testRequest.setQueryId("complexity-test-" + System.currentTimeMillis());

        PaginationRequest pagination = new PaginationRequest();
        pagination.setPageNum(1);
        pagination.setPageSize(10);
        testRequest.setPagination(pagination);

        QueryComplexityValidationResult result = secureQueryExecutor.validateQueryComplexity(testRequest);

        assertThat(result).isNotNull();
        // Simple query should pass complexity validation
        assertThat(result.getValid()).isTrue();
    }
}
