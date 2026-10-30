package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.SchemaManagementService;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelFieldUnbindProtectionTest {
    final MetaModelMapper models=mock(MetaModelMapper.class);
    final MetaFieldMapper fields=mock(MetaFieldMapper.class);
    final MetaModelFieldBindingMapper bindings=mock(MetaModelFieldBindingMapper.class);
    final SchemaManagementService schema=mock(SchemaManagementService.class);
    final ModelFieldBindingSupport support=new ModelFieldBindingSupport(models,fields,bindings,schema,null,null,null,null);
    @BeforeEach void context() {
        MetaContext.setSystemTenantContext(42L);
        var model=new Model();model.setId(10L);model.setCode("invoice");model.setStatus("published");
        var field=new Field();field.setId(20L);field.setCode("amount");
        when(models.selectById(10L)).thenReturn(model);when(fields.selectById(20L)).thenReturn(field);
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void schemaRefusalMustRetainTheBinding() {
        when(schema.removeFieldFromModel("invoice","amount")).thenReturn(SchemaOperationResult.builder().success(false).message("Field contains data").build());
        assertThatThrownBy(() -> support.unbindFieldFromModel(10L,20L)).isInstanceOf(RuntimeException.class).hasMessageContaining("binding retained");
        verify(bindings,never()).deleteByModelAndField(10L,20L);
    }
    @Test void successfulPhysicalRemovalAllowsUnbind() {
        when(schema.removeFieldFromModel("invoice","amount")).thenReturn(SchemaOperationResult.builder().success(true).build());
        when(bindings.deleteByModelAndField(10L,20L)).thenReturn(1);
        assertThat(support.unbindFieldFromModel(10L,20L)).isTrue();
    }
}
