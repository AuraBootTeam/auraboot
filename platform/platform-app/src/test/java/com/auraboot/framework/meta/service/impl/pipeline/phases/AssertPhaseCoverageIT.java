package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-stack coverage IT for {@link AssertPhase} — the SpEL assert-rule and precondition-expression
 * branches of the command pipeline's assert phase, exercised through a hand-built
 * {@link CommandPipelineContext}. The record-reading field/operator precondition + HAS_CHILDREN /
 * UNIQUE_COMPOSITE validation branches (which query live data) stay out of scope here.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AssertPhase Coverage IT — assert rules + precondition expressions")
class AssertPhaseCoverageIT {

    private static final long TENANT_ID = 991_100_001L;

    @Autowired
    private AssertPhase assertPhase;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_100_002L, "assert-test-pid", "assert-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    private BindingRule assertRule(String expr) {
        BindingRule r = new BindingRule();
        r.setRuleType("assert");
        r.setExpression(expr);
        r.setEnabled(true);
        return r;
    }

    private CommandPipelineContext ctx(Map<String, List<BindingRule>> rules, Map<String, Object> execConfig) {
        return CommandPipelineContext.builder()
                .commandCode("test:assert")
                .tenantId(TENANT_ID)
                .userId(991_100_002L)
                .payload(new HashMap<>(Map.of("amount", 100, "name", "demo")))
                .execConfig(execConfig)
                .rulesByType(rules)
                .build();
    }

    @Test
    @DisplayName("a satisfied assert rule passes; a blank-expression rule is skipped; empty config is a no-op")
    void assertPasses() {
        assertDoesNotThrow(() -> assertPhase.execute(ctx(Map.of("assert", List.of(assertRule("true"))), Map.of())));
        assertDoesNotThrow(() -> assertPhase.execute(ctx(Map.of("assert", List.of(assertRule("   "))), Map.of())));
        assertDoesNotThrow(() -> assertPhase.execute(ctx(Collections.emptyMap(), Map.of())));
    }

    @Test
    @DisplayName("a violated assert rule throws ValidationException")
    void assertFails() {
        assertThrows(ValidationException.class,
                () -> assertPhase.execute(ctx(Map.of("assert", List.of(assertRule("false"))), Map.of())));
    }

    @Test
    @DisplayName("precondition expressions: satisfied passes, violated throws")
    void preconditionExpressions() {
        Map<String, Object> pass = Map.of("preconditions",
                List.of(Map.of("expression", "true", "message", "must hold")));
        assertDoesNotThrow(() -> assertPhase.execute(ctx(Collections.emptyMap(), pass)));

        Map<String, Object> fail = Map.of("preconditions",
                List.of(Map.of("expression", "false", "message", "precondition violated")));
        assertThrows(ValidationException.class,
                () -> assertPhase.execute(ctx(Collections.emptyMap(), fail)));
    }
}
