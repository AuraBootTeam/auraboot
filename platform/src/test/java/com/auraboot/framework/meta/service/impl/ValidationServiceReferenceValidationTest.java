package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Field-level business references (extension.referenceModel → refTarget) must
 * resolve to an existing record before a command persists them. Regression
 * coverage for the dangling-reference finding: sc_monthly_metric accepted a
 * nonexistent sc_showcase_pid with a success envelope.
 */
@ExtendWith(MockitoExtension.class)
class ValidationServiceReferenceValidationTest {
    @Test void explicitPidIdentityDoesNotQueryANumericDisplayColumn() {
        FieldDefinition field=referenceField("submitted_offer","submission");
        field.getRefTarget().setTargetTable("mt_submission");
        field.getRefTarget().setValueField("pid");
        field.getRefTarget().setTargetField("sequence");
        when(dynamicDataMapper.selectByQuery(anyString(),any())).thenAnswer(invocation -> {
            String sql=invocation.getArgument(0);
            assertFalse(sql.contains("sequence"));assertFalse(sql.contains(" OR "));
            Map<?,?> params=invocation.getArgument(1);assertEquals("01OFFER",params.get("refValue"));
            return List.of(Map.of("cnt",1L));
        });
        assertTrue(service.validateField(field,"01OFFER",ValidationContext.CREATE).isValid());
    }

    @Test void databaseFailureCannotApproveAReference() {
        FieldDefinition field=referenceField("submitted_offer","submission");
        field.getRefTarget().setTargetTable("mt_submission");
        field.getRefTarget().setValueField("pid");
        when(dynamicDataMapper.selectByQuery(anyString(),any())).thenThrow(new IllegalStateException("Database unavailable"));
        var result=service.validateField(field,"01OFFER",ValidationContext.CREATE);
        assertFalse(result.isValid());assertTrue(result.getErrors().getFirst().contains("could not be verified"));
    }

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private UserService userService;

    @Mock
    private MetaModelService metaModelService;

    private ValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationServiceImpl(dynamicDataMapper, userService, metaModelService);
        MetaContext.setContext(100L, 1L, "test", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private FieldDefinition referenceField(String code, String targetEntity) {
        FieldDefinition field = new FieldDefinition();
        field.setCode(code);
        field.setName(code);
        field.setDataType("reference");
        FieldDefinition.RefTarget refTarget = new FieldDefinition.RefTarget();
        refTarget.setTargetEntity(targetEntity);
        field.setRefTarget(refTarget);
        return field;
    }

    private void mockModelTable(String modelCode, String tableName) {
        MetaModelDTO model = MetaModelDTO.builder().code(modelCode).tableName(tableName).build();
        when(metaModelService.findByCode(modelCode)).thenReturn(model);
    }

    private void mockCount(long count) {
        when(dynamicDataMapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("cnt", count)));
    }

    @Test
    @DisplayName("rejects a dangling business reference with an explicit error")
    void rejectsDanglingReference() {
        mockModelTable("showcase_all_fields", "mt_showcase_all_fields");
        mockCount(0);

        var result = service.validateField(
                referenceField("sc_showcase_pid", "showcase_all_fields"),
                "01DOESNOTEXIST", ValidationContext.CREATE);

        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Referenced record not found"));
        assertTrue(result.getErrors().get(0).contains("mt_showcase_all_fields.pid"));
    }

    @Test
    @DisplayName("accepts an existing business reference")
    void acceptsExistingReference() {
        mockModelTable("showcase_all_fields", "mt_showcase_all_fields");
        mockCount(1);

        var result = service.validateField(
                referenceField("sc_showcase_pid", "showcase_all_fields"),
                "01REAL", ValidationContext.CREATE);

        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("validates every element of a multi-reference JSON array")
    void validatesMultiReferenceElements() {
        mockModelTable("showcase_all_fields", "mt_showcase_all_fields");
        when(dynamicDataMapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("cnt", 1L)), List.of(Map.of("cnt", 0L)));

        var result = service.validateField(
                referenceField("sc_members", "showcase_all_fields"),
                "[\"01GOOD\",\"01BAD\"]", ValidationContext.CREATE);

        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("01BAD"));
    }

    @Test
    @DisplayName("skips validation when the field declares no refTarget")
    void skipsWithoutRefTarget() {
        FieldDefinition field = new FieldDefinition();
        field.setCode("sc_note");
        field.setName("sc_note");
        field.setDataType("string");

        var result = service.validateField(field, "anything", ValidationContext.CREATE);

        assertTrue(result.isValid());
        verifyNoInteractions(dynamicDataMapper, metaModelService);
    }

    @Test
    @DisplayName("delegates sys_user references to the tenant-member handler")
    void skipsSysUserTarget() {
        var result = service.validateField(
                referenceField("sc_owner", "sys_user"),
                "01USER", ValidationContext.CREATE);

        // sys_user handling asserts tenant membership through UserService; with no
        // membership mocked the handler reports the miss — the reference-existence
        // SQL path must stay out of it.
        verify(metaModelService, never()).findByCode(anyString());
        verify(dynamicDataMapper, never()).selectByQuery(anyString(), any());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("prefers the explicit legacy targetTable over the model lookup")
    void prefersExplicitTargetTable() {
        mockCount(0);
        FieldDefinition field = referenceField("sc_ref", "legacy_model");
        field.getRefTarget().setTargetTable("mt_legacy_ref");
        field.getRefTarget().setTargetField("pid");

        var result = service.validateField(field, "01MISSING", ValidationContext.CREATE);

        assertFalse(result.isValid());
        verify(metaModelService, never()).findByCode(anyString());
        assertTrue(result.getErrors().get(0).contains("mt_legacy_ref.pid"));
    }

    @Test
    @DisplayName("degrades to a warning-free skip when the target model has no table")
    void skipsWhenModelUnresolvable() {
        when(metaModelService.findByCode("ghost_model")).thenReturn(null);

        var result = service.validateField(
                referenceField("sc_ref", "ghost_model"),
                "01ANY", ValidationContext.CREATE);

        assertTrue(result.isValid());
        verify(dynamicDataMapper, never()).selectByQuery(anyString(), any());
    }
}
