package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.VirtualFieldEngine;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link VirtualFieldEngineImpl}: the pure SpEL {@code evaluate}
 * path (arithmetic / variables / strings / booleans / formula functions / error handling) plus
 * the not-found / empty-input edge branches of the dependency-graph + materialize methods. Uses
 * the wired engine (real {@code FormulaFunctionRegistry}); no model fixtures needed for these
 * paths.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("VirtualFieldEngineImpl Coverage IT — evaluate + graph/materialize edge branches")
class VirtualFieldEngineEvaluateIT {

    @Autowired
    private VirtualFieldEngine engine;

    @Test
    @DisplayName("evaluate computes arithmetic, variables and strings via SpEL")
    void evaluateExpressions() {
        assertEquals(3, engine.evaluate("1 + 2", Map.of()));
        assertEquals(7, engine.evaluate("#a + #b", Map.of("a", 3, "b", 4)));
        assertEquals("ab", engine.evaluate("#x + #y", Map.of("x", "a", "y", "b")));
        assertEquals(true, engine.evaluate("#n > 10", Map.of("n", 42)));
        assertEquals(20, engine.evaluate("#price * #qty", Map.of("price", 5, "qty", 4)));
    }

    @Test
    @DisplayName("evaluate returns null for null/empty/invalid expressions (fail-soft)")
    void evaluateNullAndInvalid() {
        assertNull(engine.evaluate(null, Map.of()));
        assertNull(engine.evaluate("", Map.of()));
        assertNull(engine.evaluate("this is not ( valid spel", Map.of()));
        assertNull(engine.evaluate("#missingVar.someMethod()", Map.of()));
    }

    @Test
    @DisplayName("evaluate tolerates a null context map")
    void evaluateNullContext() {
        assertEquals(5, engine.evaluate("2 + 3", null));
    }

    @Test
    @DisplayName("graph methods + materialize reject an unknown model (getModelDefinition throws)")
    void graphMethodsUnknownModel() {
        assertThrows(MetaServiceException.class, () -> engine.validateDependencyGraph("no_such_model_xyz"));
        assertThrows(MetaServiceException.class, () -> engine.getComputationOrder("no_such_model_xyz"));
        assertThrows(MetaServiceException.class,
                () -> engine.materialize("no_such_model_xyz", "rec-1", List.of("someField")));
    }

    @Test
    @DisplayName("materialize short-circuits (no model lookup) when no fields changed")
    void materializeNoChangedFields() {
        engine.materialize("no_such_model_xyz", "rec-1", List.of()); // empty -> early return, no throw
        assertTrue(true);
    }
}
