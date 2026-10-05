package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Characterizes master/detail orchestration, tenant constraints and per-row failures. */
class DynamicJointSaveSupportTest {
    final MetaModelService metadata = mock(MetaModelService.class);
    final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    final DynamicJointSaveSupport.CreateOperation create = mock(DynamicJointSaveSupport.CreateOperation.class);
    final DynamicJointSaveSupport.UpdateOperation update = mock(DynamicJointSaveSupport.UpdateOperation.class);
    final DynamicJointSaveSupport.GetModelDefinitionOperation lookup = mock(DynamicJointSaveSupport.GetModelDefinitionOperation.class);
    final DynamicJointSaveSupport.AssertWritableOperation writable = mock(DynamicJointSaveSupport.AssertWritableOperation.class);
    final DynamicJointSaveSupport service = new DynamicJointSaveSupport(metadata, mapper, create, update, lookup, writable);

    @BeforeEach void context() { MetaContext.setContext(42L, 43L, "user-43", "tester"); }
    @AfterEach void clearContext() { MetaContext.clear(); }

    RelationDefinition relation(String type) {
        return RelationDefinition.builder().name("lines").targetModel("invoice_line").targetTable("invoice_lines")
                .sourceField("invoice_id").targetField("parent_id").joinTable("invoice_links")
                .relationType(RelationDefinition.RelationType.valueOf(type)).build();
    }

    void model(RelationDefinition relation) {
        when(lookup.execute("invoice")).thenReturn(ModelDefinition.builder().code("invoice").relations(List.of(relation)).build());
        when(lookup.execute("invoice_line")).thenReturn(ModelDefinition.builder().code("invoice_line").build());
        when(metadata.getPrimaryKeyField("invoice")).thenReturn(FieldDefinition.builder().code("pid").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ONE_TO_MANY", "MANY_TO_MANY"})
    void replacementUpdatesMasterDeletesOnlyThisTenantsChildrenAndInjectsForeignKey(String type) {
        model(relation(type));
        when(update.execute(eq("invoice"), eq("invoice-42"), anyMap())).thenReturn(Map.of("pid", "invoice-42"));
        when(create.execute(eq("invoice_line"), anyMap())).thenAnswer(call -> call.getArgument(1));
        var child = Map.<String, Object>of("amount", 12, "parent_id", "forged");
        var request = JointSubTableSaveRequest.builder().masterData(Map.of("pid", "invoice-42"))
                .tables(Map.of("rows", List.of(child))).relationMappings(Map.of("rows", "lines")).replaceExisting(true).build();
        var result = service.saveWithRelations("invoice", request);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getMasterId()).isEqualTo("invoice-42");
        assertThat(result.getSubTableCounts()).containsEntry("lines", 1);
        assertThat(result.getSavedRecords().get("lines").get(0)).containsEntry("parent_id", "invoice-42");
        assertThat(child).containsEntry("parent_id", "forged");
        verify(mapper).delete("ONE_TO_MANY".equals(type) ? "invoice_lines" : "invoice_links",
                Map.of("ONE_TO_MANY".equals(type) ? "parent_id" : "invoice_id", "invoice-42", "tenant_id", 42L));
    }

    @Test
    void creationAggregatesChildFailuresAndRetainsSuccessfulRows() {
        model(relation("ONE_TO_MANY"));
        when(create.execute(eq("invoice"), anyMap())).thenReturn(Map.of("pid", "invoice-new"));
        when(create.execute(eq("invoice_line"), anyMap())).thenReturn(Map.of("pid", "line-1"))
                .thenThrow(new MetaServiceException("invalid amount"));
        var request = JointSubTableSaveRequest.builder().masterData(Map.of("title", "Invoice"))
                .tables(Map.of("invoice_line", List.of(Map.of("amount", 12), Map.of("amount", -1)))).build();
        var result = service.saveWithRelations("invoice", request);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getMasterId()).isEqualTo("invoice-new");
        assertThat(result.getSubTableCounts()).containsEntry("invoice_line", 1);
        assertThat(result.getSavedRecords().get("invoice_line")).containsExactly(Map.of("pid", "line-1"));
        assertThat(result.getSubTableErrors().get("invoice_line").get(0).getRowIndex()).isEqualTo(1);
        assertThat(result.getSubTableErrors().get("invoice_line").get(0).getMessage()).isEqualTo("invalid amount");
        verify(mapper, never()).delete(anyString(), anyMap());
    }

    @Test
    void unknownRelationAndMasterFailuresDoNotClaimSuccess() {
        model(relation("ONE_TO_MANY"));
        when(create.execute(eq("invoice"), anyMap())).thenReturn(Map.of("pid", "invoice-new"));
        var request = JointSubTableSaveRequest.builder().masterData(Map.of("title", "Invoice"))
                .tables(Map.of("unknown", List.of(Map.of("amount", 12)))).build();
        assertThat(service.saveWithRelations("invoice", request).getErrors()).containsExactly("Relation 'unknown' not found in model invoice");
        when(create.execute(eq("invoice"), anyMap())).thenThrow(new MetaServiceException("master denied"));
        assertThat(service.saveWithRelations("invoice", request).getErrors()).containsExactly("Master save failed: master denied");
        verify(create, never()).execute(eq("invoice_line"), anyMap());
    }

    @Test
    void invalidInputFailsBeforeMasterWrite() {
        assertThatThrownBy(() -> service.saveWithRelations("invoice", null)).isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> service.saveWithRelations("invoice", new JointSubTableSaveRequest())).isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> service.saveWithRelations("bad;model", new JointSubTableSaveRequest())).isInstanceOf(MetaServiceException.class);
        verifyNoInteractions(create, update, mapper);
    }
}
