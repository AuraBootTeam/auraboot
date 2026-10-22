package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.MetricCompileException;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-stack coverage IT for {@link SemanticQueryService} compile/validation branches —
 * a query with no metrics, a cross-model query, and an unknown model prefix are each rejected
 * with {@link MetricCompileException}, across validateQuery / explainQuery / executeQuery. The
 * happy compile+execute path needs seeded ab_semantic_model + ab_semantic_metric and stays out
 * of scope here.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SemanticQueryService Coverage IT — compile/validation rejects")
class SemanticQueryServiceCoverageIT {

    private static final long TENANT_ID = 991_700_001L;
    private static final long USER_ID = 991_700_002L;

    @Autowired
    private SemanticQueryService semanticQueryService;

    private final UserContext user = new UserContext(USER_ID, TENANT_ID, Map.of());

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "sem-test-pid", "sem-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    private SemanticQueryRequest req(List<String> metrics) {
        SemanticQueryRequest r = new SemanticQueryRequest();
        r.setMetrics(metrics);
        return r;
    }

    @Test
    @DisplayName("validateQuery rejects empty metrics, cross-model metrics, and unknown models")
    void validateRejects() {
        assertThrows(MetricCompileException.class,
                () -> semanticQueryService.validateQuery(req(List.of()), user));
        assertThrows(MetricCompileException.class,
                () -> semanticQueryService.validateQuery(req(List.of("alpha.amount", "beta.count")), user));
        assertThrows(MetricCompileException.class,
                () -> semanticQueryService.validateQuery(req(List.of("no_such_model.metric")), user));
    }

    @Test
    @DisplayName("explainQuery and executeQuery also reject an unknown model")
    void explainAndExecuteReject() {
        assertThrows(MetricCompileException.class,
                () -> semanticQueryService.explainQuery(req(List.of("no_such_model.metric")), user));
        assertThrows(MetricCompileException.class,
                () -> semanticQueryService.executeQuery(req(List.of("no_such_model.metric")), user));
    }
}
