package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FieldPredicateAuthorizationSupportTest {
    private final FieldPermissionService fields = mock(FieldPermissionService.class);
    private final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    private final FieldMaskService masks = mock(FieldMaskService.class);
    private final FieldPredicateAuthorizationSupport support = new FieldPredicateAuthorizationSupport(fields, policies, masks);
    private final ModelDefinition model = ModelDefinition.builder().code("counter").fields(List.of(
            FieldDefinition.builder().code("amount").columnName("stored_amount").build())).build();

    @BeforeEach void context() {
        MetaContext.setContext(1L, 7L, "user", "user");
        MetaContext.setMemberId(8L);
        when(fields.getFieldPermissions(8L, "counter")).thenReturn(FieldPermissionSet.allAllowed(Set.of("amount")));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private void check() { support.requireReadable(model, Set.of("amount")); }

    @Test void readableUnmaskedFieldCanBeUsedWithoutEditPermission() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of("amount"),Set.of(),Set.of()));
        assertDoesNotThrow(this::check);
    }
    @Test void hiddenFieldCannotBeInferredEvenIfItIsWritable() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of("amount"),Set.of("amount")));
        assertThrows(AccessDeniedException.class,this::check);
    }
    @Test void columnPolicyMaskAlsoProtectsLogicalFieldPredicate() {
        when(policies.getFieldMaskRules(1L,"counter",7L)).thenReturn(List.of(FieldMaskRule.builder().fieldCode("stored_amount").maskType("PARTIAL").build()));
        assertThrows(AccessDeniedException.class,this::check);
    }
    @Test void effectiveDetailMaskPreventsExactValueInference() {
        var config = new FieldMaskConfig();config.setFieldCode("amount");
        when(masks.getEffectiveConfigs("counter",7L,"detail")).thenReturn(List.of(config));
        assertThrows(AccessDeniedException.class,this::check);
    }
    @Test void missingFieldDecisionFailsClosed() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(null);
        assertThrows(com.auraboot.framework.meta.exception.MetaServiceException.class,this::check);
    }
    @Test void exactBoundInternalPermitKeepsItsSeparateOwnerContract() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("amount")));
        assertDoesNotThrow(()->MetaContext.runWithCommandPermitPlan("ALL",null,"counter",null,()->{check();return null;}));
        assertThrows(AccessDeniedException.class,this::check);
    }
    @Test void otherModelPermitCannotReadHiddenPredicate() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("amount")));
        assertThrows(AccessDeniedException.class,()->MetaContext.runWithCommandPermitPlan("ALL",null,"other",null,()->{check();return null;}));
    }
    @Test void machineCannotBorrowAnInternalPermitToProbeAnUnreadableField() {
        var principal = new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(
                1L,"inst","Integration",Set.of(),"app","inst",null,null);
        var decision = new com.auraboot.framework.application.security.MachineDataAccessContext.Decision(
                1L,"inst","counter",Set.of("update"),Set.of("pid"),Set.of("amount"),List.of());
        try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal);
             var authority = com.auraboot.framework.application.security.MachineDataAccessContext.authorize(java.util.Map.of("counter",decision))) {
            assertThrows(AccessDeniedException.class,()->MetaContext.runWithCommandPermitPlan(
                    "ALL",null,"counter",null,()->{check();return null;}));
        }
    }

    @Test void machineReadablePredicateUsesItsOwnDecision() {
        var principal = new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(
                1L,"inst","Integration",Set.of(),"app","inst",null,null);
        var decision = new com.auraboot.framework.application.security.MachineDataAccessContext.Decision(
                1L,"inst","counter",Set.of("update"),Set.of("pid","amount"),Set.of("amount"),List.of());
        try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal);
             var authority = com.auraboot.framework.application.security.MachineDataAccessContext.authorize(java.util.Map.of("counter",decision))) {
            assertDoesNotThrow(this::check);
        }
    }

    @Test void missingMemberCannotDisableTheFieldDecision() {
        MetaContext.setMemberId(null);
        when(fields.getFieldPermissions(null,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("amount")));
        assertThrows(AccessDeniedException.class,this::check);
    }

    @Test void globalPermitCannotReadHiddenPredicates() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("amount")));
        assertThrows(AccessDeniedException.class,()->MetaContext.runWithCommandPermitScope("ALL",()->{check();return null;}));
    }

    @Test void businessPredicateHonorsMetadataVisibilityEvenInsideABoundCommand() {
        when(fields.getFieldPermissions(8L,"counter")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("amount")));
        var decision=new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()),1L,7L,8L,"counter","root",
                List.of(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"counter.manage",
                        new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,Set.of("amount"),Set.of("amount")))));
        assertThrows(AccessDeniedException.class,()->MetaContext.runWithCommandPermitPlan("TARGET",1L,"counter","root",()->{
            support.requireBusinessReadable(model,Set.of("amount"),decision);return null;
        }));
    }

}
