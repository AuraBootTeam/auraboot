package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
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
                () -> service.incrementWithinCap("cr_cj_profile", "rec-1", "no_such_field", null, 1L));
    }

    @Test
    void non_numeric_field_throws_illegal_argument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_name", null, 1L));
    }

    @Test
    void increment_passes_null_cap_to_mapper() {
        when(mapper.atomicIncrementReturning(anyString(), anyString(), isNull(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of(Map.of("new_value", 5L)));

        long result = service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_followed_count", null, 1L);

        assertEquals(5L, result);
        verify(mapper).atomicIncrementReturning(
                eq("cr_cj_profile"), eq("cr_cj_followed_count"), isNull(),
                eq("pid"), anyString(), eq(1L), eq("rec-1"), eq(1L), eq(7L));
    }

    @Test
    void returns_new_value_from_mapper() {
        when(mapper.atomicIncrementReturning(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of(Map.of("new_value", 42L)));

        long result = service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_view_count", "cr_cj_view_count", 1L);

        assertEquals(42L, result);
    }

    @Test
    void empty_mapper_result_returns_minus_one() {
        when(mapper.atomicIncrementReturning(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(List.of());

        long result = service.incrementWithinCap("cr_cj_profile", "rec-1", "cr_cj_view_count", "cr_cj_view_count", 1L);

        assertEquals(-1L, result);
    }
}
