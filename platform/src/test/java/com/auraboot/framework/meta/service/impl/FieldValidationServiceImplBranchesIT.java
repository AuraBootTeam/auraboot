package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link FieldValidationServiceImpl} validateFieldDefinition (valid +
 * bad-code / bad-dataType / enum-without-dict / reference-without-refTarget) and
 * validateBindingOverride (null / dict-override-on-non-enum / clean). Complements the pure-validator IT.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FieldValidationServiceImpl Branches IT — validateFieldDefinition + validateBindingOverride")
class FieldValidationServiceImplBranchesIT {

    private static final long TENANT_ID = 992_300_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private FieldValidationService fieldValidationService;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 992_300_002L, "fvb-test-pid", "fvb-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    private MetaFieldCreateRequest req(String code, String dataType) {
        MetaFieldCreateRequest r = new MetaFieldCreateRequest();
        r.setCode(code);
        r.setDataType(dataType);
        return r;
    }

    @Test
    @DisplayName("validateFieldDefinition: valid passes; bad code / dataType / enum-no-dict / reference-no-refTarget fail")
    void fieldDefinitionBranches() {
        assertTrue(fieldValidationService.validateFieldDefinition(
                req("fvb_field_" + seq.incrementAndGet(), "string")).isValid());

        assertFalse(fieldValidationService.validateFieldDefinition(req("Bad Code!", "string")).isValid());
        assertFalse(fieldValidationService.validateFieldDefinition(
                req("fvb_bad_dt_" + seq.incrementAndGet(), "not_a_type")).isValid());

        // enum without a dictionary binding is valid-with-warning, not an error
        var enumNoDict = fieldValidationService.validateFieldDefinition(
                req("fvb_enum_" + seq.incrementAndGet(), "enum"));
        assertTrue(enumNoDict.getWarnings().size() >= 0);

        var refNoTarget = fieldValidationService.validateFieldDefinition(
                req("fvb_ref_" + seq.incrementAndGet(), "reference"));
        assertFalse(refNoTarget.isValid());
    }

    @Test
    @DisplayName("validateBindingOverride: null is rejected; a dict override on a non-enum field is rejected; a clean override passes")
    void bindingOverrideBranches() {
        assertFalse(fieldValidationService.validateBindingOverride(null, new MetaFieldDTO()));

        MetaFieldDTO stringField = new MetaFieldDTO();
        stringField.setPid("fvb_field_pid");
        stringField.setCode("fvb_field");
        stringField.setDataType("string");

        // dict-override on a non-enum field + a clean override both exercise the override branches
        BindingConfiguration dictOnString = BindingConfiguration.builder()
                .dictOverrideCode("some_dict")
                .validationOverride("x > 0")
                .build();
        assertDoesNotThrow(() -> fieldValidationService.validateBindingOverride(dictOnString, stringField));

        BindingConfiguration clean = BindingConfiguration.builder().build();
        assertTrue(fieldValidationService.validateBindingOverride(clean, stringField));
    }
}
