package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A query command that declares input fields used to take the implicit field-map path, whose
 * fall-through is INSERT: the "filter" was saved as a record. The executor refuses the path.
 */
class CommandFieldMapExecutorQueryGuardTest {

    private final DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);

    @Test
    void aQueryNeverReachesTheImplicitWritePath() {
        assertThatThrownBy(() -> executor().executeImplicitFieldMapPhase(
                Map.of("type", "query", "inputFields", List.of("inv_sc_remark")),
                Map.of("inv_sc_remark", "a filter, not a value"),
                100L,
                new CommandExecuteRequest(),
                command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query")
                .hasMessageContaining("inv:query_stock_check");
        verifyNoInteractions(dynamicDataMapper);
    }

    @Test
    void theClientOperationTypeDoesNotTurnAQueryIntoAWrite() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("create");

        assertThatThrownBy(() -> executor().executeImplicitFieldMapPhase(
                Map.of("type", "query", "inputFields", List.of("inv_sc_remark")),
                Map.of("inv_sc_remark", "a filter, not a value"),
                100L,
                request,
                command()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(dynamicDataMapper);
    }

    private CommandFieldMapExecutor executor() {
        return new CommandFieldMapExecutor(
                dynamicDataMapper,
                mock(MetaModelService.class),
                mock(com.auraboot.framework.permission.service.FieldPermissionService.class),
                mock(DataPermissionEngine.class),
                mock(DataDomainService.class),
                mock(CommandModelMutationSupport.class));
    }

    private CommandDefinition command() {
        CommandDefinition command = new CommandDefinition();
        command.setCode("inv:query_stock_check");
        command.setModelCode("inv_stock_check");
        return command;
    }
}
