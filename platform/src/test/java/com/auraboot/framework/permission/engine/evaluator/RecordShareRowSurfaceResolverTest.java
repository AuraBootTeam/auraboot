package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.SharedRootReference;
import com.auraboot.framework.permission.service.RecordShareService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordShareRowSurfaceResolverTest {

    @Mock private MetaModelService metaModelService;
    @Mock private RecordShareService recordShareService;
    @InjectMocks private RecordShareRowSurfaceResolver resolver;

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    private MetaModelDTO modelWithDeclaration(Map<String, Object> extension) {
        MetaModelDTO model = org.mockito.Mockito.mock(MetaModelDTO.class);
        when(model.getExtension()).thenReturn(extension);
        return model;
    }

    @Test
    void resolvesDeclaredEdgeWhenRootIsShared() {
        MetaContext.setContext(99L, 7L, "user_pid_7", "user");
        MetaModelDTO model = modelWithDeclaration(Map.of(
                "recordShare", Map.of("rootModel", "qo_quote_common", "referenceField", "qo_ql_quote_id")));
        when(metaModelService.findByCode("qo_quote_line_common")).thenReturn(model);
        when(metaModelService.getModelFields("qo_quote_line_common")).thenReturn(List.of(
                FieldDefinition.builder()
                        .code("qo_ql_quote_id")
                        .dataType("reference")
                        .refTarget(FieldDefinition.RefTarget.builder()
                                .targetEntity("qo_quote_common")
                                .build())
                        .build()));
        when(recordShareService.getSharedRecordPids(99L, "qo_quote_common", 1L, "user_pid_7", "read"))
                .thenReturn(List.of("root_1", "root_2"));

        List<SharedRootReference> surface =
                resolver.resolveSharedRootReferences(99L, 1L, "qo_quote_line_common", "read");

        assertEquals(1, surface.size());
        assertEquals("qo_ql_quote_id", surface.get(0).referenceField());
        assertEquals(List.of("root_1", "root_2"), surface.get(0).rootRecordPids());
        verify(recordShareService).getSharedRecordPids(
                99L, "qo_quote_common", 1L, "user_pid_7", "read");
    }

    @Test
    void withoutDeclarationReturnsEmptyAndNeverQueriesShares() {
        MetaContext.setContext(99L, 7L, "user_pid_7", "user");
        MetaModelDTO model = modelWithDeclaration(Map.of("icon", "List"));
        when(metaModelService.findByCode("some_model")).thenReturn(model);

        assertTrue(resolver.resolveSharedRootReferences(99L, 1L, "some_model", "read").isEmpty());
        verify(recordShareService, never()).getSharedRecordPids(any(), any(), anyLong(), any(), any());
    }

    @Test
    void unknownDeclaredFieldFailsClosed() {
        MetaContext.setContext(99L, 7L, "user_pid_7", "user");
        MetaModelDTO model = modelWithDeclaration(Map.of(
                "recordShare", Map.of("rootModel", "qo_quote_common", "referenceField", "not_a_field")));
        when(metaModelService.findByCode("qo_quote_line_common")).thenReturn(model);
        when(metaModelService.getModelFields("qo_quote_line_common")).thenReturn(List.of(
                FieldDefinition.builder()
                        .code("qo_ql_quote_id")
                        .dataType("reference")
                        .refTarget(FieldDefinition.RefTarget.builder()
                                .targetEntity("qo_quote_common")
                                .build())
                        .build()));

        assertTrue(resolver.resolveSharedRootReferences(99L, 1L, "qo_quote_line_common", "read").isEmpty());
        verify(recordShareService, never()).getSharedRecordPids(any(), any(), anyLong(), any(), any());
    }

    @Test
    void stringTypedFkFieldIsHonoredByValueEquality() {
        MetaContext.setContext(99L, 7L, "user_pid_7", "user");
        MetaModelDTO model = modelWithDeclaration(Map.of(
                "recordShare", Map.of("rootModel", "qo_quote_common", "referenceField", "qo_pfrh_quote_id")));
        when(metaModelService.findByCode("qo_quote_line_common")).thenReturn(model);
        when(metaModelService.getModelFields("qo_quote_line_common")).thenReturn(List.of(
                FieldDefinition.builder()
                        .code("qo_pfrh_quote_id")
                        .dataType("string")
                        .build()));
        when(recordShareService.getSharedRecordPids(99L, "qo_quote_common", 1L, "user_pid_7", "read"))
                .thenReturn(List.of("root_1"));

        List<SharedRootReference> surface =
                resolver.resolveSharedRootReferences(99L, 1L, "qo_quote_line_common", "read");

        assertEquals(1, surface.size());
        assertEquals(List.of("root_1"), surface.get(0).rootRecordPids());
    }

    @Test
    void rootWithoutSharesYieldsEmptySurface() {
        MetaContext.setContext(99L, 7L, "user_pid_7", "user");
        MetaModelDTO model = modelWithDeclaration(Map.of(
                "recordShare", Map.of("rootModel", "qo_quote_common", "referenceField", "qo_ql_quote_id")));
        when(metaModelService.findByCode("qo_quote_line_common")).thenReturn(model);
        when(metaModelService.getModelFields("qo_quote_line_common")).thenReturn(List.of(
                FieldDefinition.builder()
                        .code("qo_ql_quote_id")
                        .dataType("reference")
                        .refTarget(FieldDefinition.RefTarget.builder()
                                .targetEntity("qo_quote_common")
                                .build())
                        .build()));
        when(recordShareService.getSharedRecordPids(
                eq(99L), eq("qo_quote_common"), anyLong(), eq("user_pid_7"), eq("read")))
                .thenReturn(List.of());

        assertTrue(resolver.resolveSharedRootReferences(99L, 1L, "qo_quote_line_common", "read").isEmpty());
    }

    @Test
    void nullTenantOrMemberShortCircuits() {
        assertTrue(resolver.resolveSharedRootReferences(null, 1L, "m", "read").isEmpty());
        assertTrue(resolver.resolveSharedRootReferences(99L, null, "m", "read").isEmpty());
    }
}
