package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.exception.ValidationException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ValidationServiceBatchRelationsTest {
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final ValidationServiceImpl validation = new ValidationServiceImpl(mapper, null);
    private final ModelDefinition model = ModelDefinition.builder().code("detail")
            .fields(List.of(FieldDefinition.builder().code("name").name("Name")
                    .dataType("string").required(true).build()))
            .relations(List.of(RelationDefinition.builder().name("parent")
                    .sourceField("parent").targetTable("mt_parent").targetField("pid")
                    .required(true).build())).build();

    @BeforeEach void context() { MetaContext.setContext(17L, 23L, null, "test"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void validatesAllDistinctReferencesInOneTenantBoundRoundTrip() {
        String hostile = "x' OR 1=1 --";
        when(mapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("cnt0", 1L, "cnt1", 1L)));
        validation.validateBatchAndThrow(model, List.of(
                Map.of("name", "One", "parent", "same"),
                Map.of("name", "Two", "parent", "same"),
                Map.of("name", "Three", "parent", hostile)), ValidationContext.CREATE);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper, times(1)).selectByQuery(sql.capture(), params.capture());
        assertFalse(sql.getValue().contains(hostile));
        assertTrue(sql.getValue().contains("tenant_id = #{params.tenantId}"));
        assertEquals(Map.of("tenantId", 17L, "ref0", "same", "ref1", hostile), params.getValue());
    }

    @Test void boundsLargeBatchesAndRejectsFailureInLastChunk() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int index = 0; index < 257; index++) {
            rows.add(Map.of("name", "Student " + index, "parent", "parent-" + index));
        }
        when(mapper.selectByQuery(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> params = invocation.getArgument(1);
            assertTrue(params.size() <= 129);
            assertEquals(17L, params.get("tenantId"));
            Map<String, Object> counts = new java.util.HashMap<>();
            for (int index = 0; index < params.size() - 1; index++) {
                counts.put("cnt" + index, "parent-256".equals(params.get("ref" + index)) ? 0L : 1L);
            }
            return List.of(counts);
        });
        assertThrows(ValidationException.class,
                () -> validation.validateBatchAndThrow(model, rows, ValidationContext.CREATE));
        verify(mapper, times(3)).selectByQuery(anyString(), anyMap());
    }

    @Test void rejectsMissingOrForeignTenantReferenceInAnyRow() {
        when(mapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("cnt0", 1L, "cnt1", 0L)));
        assertThrows(ValidationException.class, () -> validation.validateBatchAndThrow(model,
                List.of(Map.of("name", "One", "parent", "local"),
                        Map.of("name", "Two", "parent", "foreign")), ValidationContext.CREATE));
    }

    @Test void retainsRequiredFieldAndRequiredRelationValidation() {
        assertThrows(ValidationException.class, () -> validation.validateBatchAndThrow(model,
                List.of(Map.of("parent", "local")), ValidationContext.CREATE));
        assertThrows(ValidationException.class, () -> validation.validateBatchAndThrow(model,
                List.of(Map.of("name", "One")), ValidationContext.CREATE));
        verifyNoInteractions(mapper);
    }

    @Test void rejectsPayloadTenantMismatchBeforeReferenceLookup() {
        assertThrows(ValidationException.class, () -> validation.validateBatchAndThrow(model,
                List.of(Map.of("name", "One", "parent", "local", "tenant_id", 18L)),
                ValidationContext.CREATE));
        verifyNoInteractions(mapper);
    }

    @Test void rejectsIncompleteResultsAndDatabaseFailure() {
        when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        List<Map<String, Object>> rows = List.of(Map.of("name", "One", "parent", "local"));
        assertThrows(ValidationException.class,
                () -> validation.validateBatchAndThrow(model, rows, ValidationContext.CREATE));
        when(mapper.selectByQuery(anyString(), anyMap())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,
                () -> validation.validateBatchAndThrow(model, rows, ValidationContext.CREATE));
    }
}
