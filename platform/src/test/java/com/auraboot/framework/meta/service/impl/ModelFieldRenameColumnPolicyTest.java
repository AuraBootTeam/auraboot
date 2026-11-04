package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.ddl.DdlDialect;
import com.auraboot.framework.meta.ddl.DdlDialectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelFieldRenameColumnPolicyTest {
    @AfterEach void clean() { MetaContext.clear(); }
    @Test void onlyResolvedCurrentTenantOwnerBindingMayDeferPhysicalConstraint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            Object[] args = java.util.Arrays.copyOfRange(call.getArguments(), 2, call.getArguments().length);
            return sql.contains("JOIN ab_meta_model_field_binding") && List.of(args).equals(List.of(1L,"plugin","contact","mt_contact","mt_contact","contact_name"));
        });
        var policy = new ModelFieldRenameColumnPolicy(jdbc);
        MetaContext.setContext(1L,2L,"actor","actor");
        try(var scope = policy.open(List.of(new ModelFieldRenameColumnPolicy.ApprovedField(1L,"plugin","contact","contact_name")))) {
            assertTrue(policy.deferNotNull("mt_contact","contact_name"));
            assertFalse(policy.deferNotNull("mt_category","contact_name"));
            assertFalse(policy.deferNotNull("mt_contact","shared_name"));
            MetaContext.setCurrentTenantId(2L);
            assertFalse(policy.deferNotNull("mt_contact","contact_name"));
            assertThrows(IllegalStateException.class, () -> policy.open(List.of()));
        }
        assertFalse(policy.deferNotNull("mt_contact","contact_name"));
    }
    @Test void durableSharedColumnPolicyDoesNotDependOnTheNextImportTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("mt_contact"),eq("contact_name"),eq("contact_name"))).thenReturn(true);
        var policy = new ModelFieldRenameColumnPolicy(jdbc);
        assertTrue(policy.deferNotNull("mt_contact","contact_name"));
        assertFalse(policy.deferNotNull("mt_category","shared_name"));
    }
    @Test void logicalRequiredStaysTrueWhileOnlyApprovedPhysicalColumnIsNullable() {
        var policy = mock(ModelFieldRenameColumnPolicy.class);
        when(policy.deferNotNull("mt_contact","contact_name")).thenReturn(true);
        var provider = mock(DdlDialectProvider.class);
        var dialect = mock(DdlDialect.class);
        when(provider.getDialect()).thenReturn(dialect);
        when(dialect.mapDataType(any())).thenReturn("VARCHAR(50)");
        var service = new SchemaManagementServiceImpl(null,null,provider,null,null,null,policy,null);
        var field = FieldDefinition.builder().code("contact_name").columnName("contact_name").dataType("string").required(true).build();
        String deferred = ReflectionTestUtils.invokeMethod(service,"generateColumnDefinition","mt_contact",field);
        assertFalse(deferred.contains("NOT NULL"));
        assertTrue(field.isRequired());
        String ordinary = ReflectionTestUtils.invokeMethod(service,"generateColumnDefinition","mt_other",field);
        assertTrue(ordinary.contains("NOT NULL"));
        field.setPrimaryKey(true);
        String primary = ReflectionTestUtils.invokeMethod(service,"generateColumnDefinition","mt_contact",field);
        assertTrue(primary.contains("NOT NULL"));
    }
}
