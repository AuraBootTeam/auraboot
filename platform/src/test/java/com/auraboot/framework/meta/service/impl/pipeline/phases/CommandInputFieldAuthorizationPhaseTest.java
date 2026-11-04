package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandInputFieldAuthorizationPhaseTest {
    final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    final CommandInputFieldAuthorizationPhase phase = new CommandInputFieldAuthorizationPhase(policies);
    final CommandPipelineContext context = CommandPipelineContext.builder().build();

    @BeforeEach void prepare() {
        MetaContext.setContext(7L,20L,"actor","Actor"); MetaContext.setMemberId(30L);
        var command = new CommandDefinition(); command.setModelCode("orders"); context.setCommand(command);
        when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of("cost"));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void declaredCommandAuthorityCannotOverrideInputFieldRestrictions() {
        context.setPayload(new HashMap<>(Map.of("cost",99,"description","unchanged")));
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("TARGET",1L,"orders","root",
                () -> { phase.execute(context); return null; })).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(context.getPayload()).containsEntry("cost",99).containsEntry("description","unchanged");
    }
    @Test void nullCannotBeUsedToClearARestrictedField() {
        var payload = new HashMap<String,Object>(); payload.put("cost",null); context.setPayload(payload);
        assertThatThrownBy(() -> phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void unrelatedInputsRemainUsableAndAreNotMutated() {
        context.setPayload(Map.of("description","new")); phase.execute(context);
        assertThat(context.getPayload()).containsExactly(Map.entry("description","new"));
    }
    @Test void emptyInputDoesNotCheckDerivedInternalWrites() {
        context.setPayload(Map.of()); phase.execute(context); verifyNoInteractions(policies);
    }
}
