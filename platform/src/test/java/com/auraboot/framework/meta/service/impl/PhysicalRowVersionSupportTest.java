package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhysicalRowVersionSupportTest {
    private ModelDefinition model(String table, String sourceType, String modelType) {
        return ModelDefinition.builder().code("version_fixture").tableName(table).sourceType(sourceType)
                .modelType(modelType).fields(List.of(FieldDefinition.builder().code("name")
                        .columnName("name").dataType("STRING").build())).build();
    }

    @Test void customAndExternallyManagedBindingsUseActualPhysicalColumn() {
        for (String table : List.of("custom_version_fixture", "ab_version_fixture")) {
            var mapper = mock(DynamicDataMapper.class);
            when(mapper.checkColumnExists(table, "row_version")).thenReturn(1);
            var sql = new QueryBuilderServiceImpl(mapper).buildConditionQuery(model(table, "physical", null), List.of()).getSql();
            assertThat(sql).contains("row_version");
            verify(mapper).checkColumnExists(table, "row_version");
        }
    }

    @Test void physicalBindingWithoutVersionColumnDoesNotInventIt() {
        var mapper = mock(DynamicDataMapper.class);
        var sql = new QueryBuilderServiceImpl(mapper).buildConditionQuery(
                model("external_unversioned", "physical", null), List.of()).getSql();
        assertThat(sql).doesNotContain("row_version");
        verify(mapper).checkColumnExists("external_unversioned", "row_version");
    }

    @Test void virtualModelsAndViewsDoNotAcquirePhysicalConcurrencyColumns() {
        var mapper = mock(DynamicDataMapper.class);
        for (var model : List.of(model("mt_virtual_fixture", "namedQuery", null), model("mt_view_fixture", "physical", "view"))) {
            assertThat(PhysicalRowVersionSupport.isVersioned(model, mapper)).isFalse();
        }
        verifyNoInteractions(mapper);
    }

    @Test void failedSchemaInspectionDoesNotSilentlyDowngradeConcurrency() {
        var mapper = mock(DynamicDataMapper.class);
        var failure = new IllegalStateException("Schema catalog unavailable");
        when(mapper.checkColumnExists("custom_version_fixture", "row_version")).thenThrow(failure);
        assertThatThrownBy(() -> new QueryBuilderServiceImpl(mapper).buildConditionQuery(
                model("custom_version_fixture", "physical", null), List.of())).isSameAs(failure);
    }
}
