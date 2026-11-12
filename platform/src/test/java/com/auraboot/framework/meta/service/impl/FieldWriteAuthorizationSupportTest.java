package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FieldWriteAuthorizationSupportTest {
    final MetaModelService metadata = mock(MetaModelService.class);
    final FieldPermissionService fields = mock(FieldPermissionService.class);
    final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    final FieldWriteAuthorizationSupport support = new FieldWriteAuthorizationSupport(metadata, fields, policies);

    @BeforeEach void actor() {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        lenient().when(metadata.getModelFields("orders")).thenReturn(List.of(
                FieldDefinition.builder().code("cost").columnName("cost_column").build(),
                FieldDefinition.builder().code("name").columnName("name_column").build(),
                FieldDefinition.builder().code("updated_by").columnName("updated_by").build()));
        lenient().when(fields.getFieldPermissions(30L, "orders"))
                .thenReturn(new FieldPermissionSet(Set.of("cost", "name"), Set.of("name"), Set.of()));
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void dslRestrictionsCoverColumnAliasesWithoutTreatingControlInputsAsWrites() {
        var keys = Set.of("cost_column", "name", "reason", "updated_by");
        assertThat(support.nonWritableInputKeys("orders", keys)).containsExactly("cost_column");
        assertThat(keys).containsExactlyInAnyOrder("cost_column", "name", "reason", "updated_by");
    }
    @Test void explicitRestrictionsRemainBindingEvenWhenDslAllowsTheField() {
        when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of("name"));
        assertThat(support.nonWritableInputKeys("orders",Set.of("name_column","cost")))
                .containsExactlyInAnyOrder("name_column","cost");
    }
    @Test void aPhysicalPolicyFieldAlsoRestrictsItsLogicalFieldCode() {
        when(fields.getFieldPermissions(30L,"orders")).thenReturn(FieldPermissionSet.allAllowed(Set.of("cost","name")));
        when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of("cost_column"));
        assertThat(support.nonWritableInputKeys("orders",Set.of("cost"))).containsExactly("cost");
    }
    @Test void missingFieldPermissionsCannotBecomeAnUnrestrictedWrite() {
        when(fields.getFieldPermissions(30L,"orders")).thenReturn(null);
        assertThatThrownBy(()->support.nonWritableInputKeys("orders",Set.of("name"))).isInstanceOf(MetaServiceException.class);
    }
    @Test void missingMetadataCannotBecomeAnUnrestrictedWrite() {
        when(metadata.getModelFields("orders")).thenReturn(null);
        assertThatThrownBy(()->support.nonWritableInputKeys("orders",Set.of("name"))).isInstanceOf(MetaServiceException.class);
    }
    @Test void nonMemberAuthorityDoesNotBorrowHumanFieldRoles() {
        MetaContext.clear();
        MetaContext.setSystemTenantContext(7L);
        assertThat(support.nonWritableInputKeys("orders",Set.of("name"))).isEmpty();
        verifyNoInteractions(metadata,fields);
    }
    @Test void emptyInputNeedsNoPermissionLookup() {
        assertThat(support.nonWritableInputKeys("orders",Set.of())).isEmpty();
        verifyNoInteractions(metadata,fields,policies);
    }

    @Test void missingHumanMembershipCannotDisableDslFieldRestrictions() {
        MetaContext.clearMemberId();
        assertThatThrownBy(() -> support.nonWritableInputKeys("orders", Set.of("cost_column")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void completeBusinessFieldsRetainMandatoryLogicalAndPhysicalRestrictions() {
        when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of("cost_column"));
        assertThat(support.nonWritablePolicyInputKeys("orders",Set.of("cost","name_column"))).containsExactly("cost");
        verify(fields).getFieldPermissions(30L,"orders");
    }

    @Test void completeBusinessFieldsCannotBypassMetadataEditRoles() {
        assertThat(support.nonWritablePolicyInputKeys("orders",Set.of("cost_column","name"))).containsExactly("cost_column");
    }

}
