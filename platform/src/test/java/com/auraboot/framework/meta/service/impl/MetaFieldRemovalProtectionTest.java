package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MetaFieldRemovalProtectionTest {
    final MetaModelService models=mock(MetaModelService.class);
    final MetaFieldMapper fields=mock(MetaFieldMapper.class);
    final MetaModelFieldBindingMapper bindings=mock(MetaModelFieldBindingMapper.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final FieldDeletionGuard guard=mock(FieldDeletionGuard.class);
    final MetaFieldServiceImpl service=mock(MetaFieldServiceImpl.class,CALLS_REAL_METHODS);
    @BeforeEach void setup() {
        MetaContext.setSystemTenantContext(42L);
        ReflectionTestUtils.setField(service,"metaModelService",models);ReflectionTestUtils.setField(service,"metaFieldMapper",fields);
        ReflectionTestUtils.setField(service,"fieldBindingMapper",bindings);ReflectionTestUtils.setField(service,"jdbcTemplate",jdbc);
        ReflectionTestUtils.setField(service,"fieldDeletionGuard",guard);
        var model=new MetaModelDTO();model.setId(10L);model.setCode("invoice");
        when(models.findByCode("invoice")).thenReturn(model);when(models.getTableName("invoice")).thenReturn("mt_invoice");
        when(models.getFieldDefinition("invoice","amount")).thenReturn(FieldDefinition.builder().code("amount").columnName("amount").build());
        var field=new Field();field.setId(20L);field.setCode("amount");when(fields.findCurrentByCode("amount")).thenReturn(field);
    }
    @AfterEach void clear() {MetaContext.clear();}
    RemoveFieldRequest request(boolean probe) {return RemoveFieldRequest.builder().modelCode("invoice").storageCode("invoice_amount").refuseIfDataExists(probe).build();}
    @Test void aDefinitionStillUsedByAnotherModelIsNotHardDeleted() {
        when(models.unbindFieldFromModel(10L,20L)).thenReturn(true);
        when(bindings.findByFieldId(20L)).thenReturn(java.util.List.of(new ModelFieldBinding()));
        service.removeFromModel(request(false));
        verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(guard);
    }
    @Test void unsuccessfulUnbindCannotContinueToMetadataDeletion() {
        when(models.unbindFieldFromModel(10L,20L)).thenReturn(false);
        assertThatThrownBy(() -> service.removeFromModel(request(false))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jdbc,guard);
    }
    @Test void requestedProbeFailureIsNotAnEmptyColumnOrACleanupRequest() {
        var failure=new org.springframework.dao.DataAccessResourceFailureException("probe unavailable");
        when(jdbc.queryForObject(anyString(),eq(Boolean.class))).thenThrow(failure);
        assertThatThrownBy(() -> service.removeFromModel(request(true))).isSameAs(failure);
        verify(models,never()).unbindFieldFromModel(anyLong(),anyLong());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
