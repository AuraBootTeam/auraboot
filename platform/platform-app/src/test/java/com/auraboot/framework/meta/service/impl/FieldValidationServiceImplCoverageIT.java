package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.FieldValidationService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link FieldValidationServiceImpl} pure validators:
 * code-format, data-type, and reference-target (false branches). No model fixtures needed.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FieldValidationServiceImpl Coverage IT — code/dataType/refTarget validators")
class FieldValidationServiceImplCoverageIT {

    @Autowired
    private FieldValidationService fieldValidationService;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(990_900_001L, 990_000_008L, "fv-test-pid", "fv-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("validateCodeFormat accepts snake_case codes, rejects blanks/illegal chars")
    void codeFormat() {
        assertTrue(fieldValidationService.validateCodeFormat("order_amount"));
        assertFalse(fieldValidationService.validateCodeFormat(""));
        assertFalse(fieldValidationService.validateCodeFormat(null));
        assertFalse(fieldValidationService.validateCodeFormat("Bad Code!"));
        assertFalse(fieldValidationService.validateCodeFormat("123starts_with_digit"));
    }

    @Test
    @DisplayName("validateDataType accepts supported types, rejects unknown/blank")
    void dataType() {
        assertTrue(fieldValidationService.validateDataType("string"));
        assertTrue(fieldValidationService.validateDataType("integer"));
        assertFalse(fieldValidationService.validateDataType("not_a_type"));
        assertFalse(fieldValidationService.validateDataType(""));
        assertFalse(fieldValidationService.validateDataType(null));
    }

    @Test
    @DisplayName("validateRefTarget rejects null/empty/missing-modelCode/unknown-model")
    void refTarget() {
        assertFalse(fieldValidationService.validateRefTarget(null));
        assertFalse(fieldValidationService.validateRefTarget(Map.of()));
        assertFalse(fieldValidationService.validateRefTarget(Map.of("other", "x")));
        assertFalse(fieldValidationService.validateRefTarget(Map.of("modelCode", "no_such_model_xyz")));
    }
}
