package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure-Mockito guard tests for DynamicDataServiceImpl.incrementWithinCap / increment.
 * Verifies: unknown-field guard, non-numeric-type guard, null-cap pass-through,
 * mapper delegation, and capped-value round-trip.
 *
 * No Spring context — instantiates DynamicDataServiceImpl directly with null deps
 * for paths not exercised by these tests, then injects only the two deps needed.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceAtomicIncrementGuardTest {

    @Mock MetaModelService metadataService;
    @Mock DynamicDataMapper mapper;

    DynamicDataServiceImpl service;

    // Model used across tests — has two integer fields and one string field
    private ModelDefinition testModel;

    // PK field definition shared across tests
    private FieldDefinition pidField;

    @BeforeEach
    void setUp() {
        // DynamicDataServiceImpl is @RequiredArgsConstructor over 22 private final fields.
        // Pass one null per constructor parameter — 22 nulls.
        service = new DynamicDataServiceImpl(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "metadataService", metadataService);
        ReflectionTestUtils.setField(service, "dynamicDataMapper", mapper);

        testModel = ModelDefinition.builder()
                .code("cr_cj_profile")
                .tableName("cr_cj_profile")
                .softDelete(false)
                .fields(List.of(
                        FieldDefinition.builder().code("cr_cj_view_count").columnName("cr_cj_view_count").dataType("integer").build(),
                        FieldDefinition.builder().code("cr_cj_followed_count").columnName("cr_cj_followed_count").dataType("integer").build(),
                        FieldDefinition.builder().code("cr_cj_name").columnName("cr_cj_name").dataType("string").build()
                ))
                .build();

        pidField = FieldDefinition.builder()
                .code("pid")
                .columnName("pid")
                .dataType("string")
                .primaryKey(true)
                .build();

        when(metadataService.getModelDefinition("cr_cj_profile")).thenReturn(Optional.of(testModel));

        MetaContext.setContext(1L, 7L, null, "system");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void unknown_counter_field_throws_illegal_argument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.incrementWithinCap("cr_cj_profile", "rec-1", "no_such_field", 1L, null));
    }

    @Test
    void non_numeric_field_throws_illegal_argument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_name", 1L, null));
    }

    @Test
    void immutable_model_rejects_atomic_increment_before_mapper_or_primary_key_lookup() {
        ModelDefinition immutable = ModelDefinition.builder()
                .code("cr_cj_profile")
                .tableName("cr_cj_profile")
                .immutable(true)
                .fields(testModel.getFields())
                .build();
        when(metadataService.getModelDefinition("cr_cj_profile")).thenReturn(Optional.of(immutable));

        MetaServiceException error = assertThrows(MetaServiceException.class,
                () -> service.incrementWithinCap(
                        "cr_cj_profile", "rec-1", "cr_cj_followed_count", 1L, null));

        assertTrue(error.getMessage().contains("immutable"));
        verify(metadataService, never()).getPrimaryKeyField(anyString());
        verifyNoInteractions(mapper);
    }

    @Test
    void command_owned_counter_rejects_direct_atomic_increment() {
        ModelDefinition protectedModel = protectedCounterModel();
        when(metadataService.getDefinitionByCode("cr_cj_profile")).thenReturn(protectedModel);
        when(metadataService.getModelDefinition("cr_cj_profile"))
                .thenReturn(Optional.of(protectedModel));

        MetaServiceException error = assertThrows(MetaServiceException.class,
                () -> service.incrementWithinCap(
                        "cr_cj_profile", "rec-1", "cr_cj_followed_count", 1L, null));

        assertTrue(error.getMessage().contains("FIELD_WRITER_DENIED"));
        verify(metadataService, never()).getPrimaryKeyField(anyString());
        verifyNoInteractions(mapper);
    }

    @Test
    void exact_authorized_command_may_increment_its_owned_counter() {
        ModelDefinition protectedModel = protectedCounterModel();
        when(metadataService.getDefinitionByCode("cr_cj_profile")).thenReturn(protectedModel);
        when(metadataService.getModelDefinition("cr_cj_profile"))
                .thenReturn(Optional.of(protectedModel));
        when(metadataService.getPrimaryKeyField("cr_cj_profile")).thenReturn(pidField);
        when(mapper.atomicIncrementReturning(anyString(), anyString(), isNull(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of(Map.of("new_value", 8L)));

        Optional<Long> result = MetaContext.runWithCommandPermitPlan(
                "ALL", null, "cr_cj_profile", "rec-1",
                () -> MetaContext.runWithAuthorizedCommandCode(
                        "crm:follow_customer",
                        () -> service.incrementWithinCap(
                                "cr_cj_profile", "rec-1", "cr_cj_followed_count", 1L, null)));

        assertEquals(Optional.of(8L), result);
        verify(mapper).atomicIncrementReturning(
                eq("cr_cj_profile"), eq("cr_cj_followed_count"), isNull(),
                eq("pid"), anyString(), eq(1L), eq("rec-1"), eq(1L), eq(7L));
    }

    @Test
    void command_only_model_rejects_direct_create_before_permissions_or_mapper() {
        ModelDefinition protectedModel = ModelDefinition.builder()
                .code("cr_cj_profile")
                .tableName("cr_cj_profile")
                .immutable(true)
                .commandOnlyCreate(true)
                .fields(testModel.getFields())
                .build();
        when(metadataService.getModelDefinition("cr_cj_profile"))
                .thenReturn(Optional.of(protectedModel));

        MetaServiceException error = assertThrows(MetaServiceException.class,
                () -> service.create("cr_cj_profile", new java.util.HashMap<>(Map.of(
                        "cr_cj_name", "forged"))));

        assertTrue(error.getMessage().contains("authorized command"));
        verifyNoInteractions(mapper);
    }

    @Test
    void increment_passes_null_cap_to_mapper() {
        when(metadataService.getDefinitionByCode("cr_cj_profile")).thenReturn(testModel);
        when(metadataService.getPrimaryKeyField("cr_cj_profile")).thenReturn(pidField);
        when(mapper.atomicIncrementReturning(anyString(), anyString(), isNull(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of(Map.of("new_value", 5L)));

        Optional<Long> result = service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_followed_count", 1L, null);

        assertTrue(result.isPresent());
        assertEquals(5L, result.get());
        verify(mapper).atomicIncrementReturning(
                eq("cr_cj_profile"), eq("cr_cj_followed_count"), isNull(),
                eq("pid"), anyString(), eq(1L), eq("rec-1"), eq(1L), eq(7L));
    }

    @Test
    void returns_new_value_from_mapper() {
        when(metadataService.getDefinitionByCode("cr_cj_profile")).thenReturn(testModel);
        when(metadataService.getPrimaryKeyField("cr_cj_profile")).thenReturn(pidField);
        when(mapper.atomicIncrementReturning(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of(Map.of("new_value", 50L)));

        // Use distinct counter and cap fields
        Optional<Long> result = service.incrementWithinCap("cr_cj_profile", "rec-1",
                "cr_cj_view_count", 1L, "cr_cj_followed_count");

        assertTrue(result.isPresent());
        assertEquals(50L, result.get());
    }

    @Test
    void empty_mapper_result_returns_empty_optional() {
        when(metadataService.getDefinitionByCode("cr_cj_profile")).thenReturn(testModel);
        when(metadataService.getPrimaryKeyField("cr_cj_profile")).thenReturn(pidField);
        when(mapper.atomicIncrementReturning(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of());

        Optional<Long> result = service.incrementWithinCap("cr_cj_profile", "rec-1",
                "cr_cj_view_count", 1L, "cr_cj_followed_count");

        assertTrue(result.isEmpty());
    }

    private ModelDefinition protectedCounterModel() {
        return ModelDefinition.builder()
                .code("cr_cj_profile")
                .tableName("cr_cj_profile")
                .softDelete(false)
                .fields(List.of(
                        FieldDefinition.builder()
                                .code("cr_cj_followed_count")
                                .columnName("cr_cj_followed_count")
                                .dataType("integer")
                                .allowedWriterCommands(List.of("crm:follow_customer"))
                                .build()))
                .build();
    }
}
