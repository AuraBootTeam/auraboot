package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordCapabilityServiceImplPreconditionTest {

    @Test
    void filtersCustomCommandsWhenRecordPreconditionsDoNotMatch() {
        CommandService commandService = mock(CommandService.class);
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        UserPermissionService permissionService = mock(UserPermissionService.class);
        PageSchemaService pageSchemaService = mock(PageSchemaService.class);
        RecordCapabilityServiceImpl service = new RecordCapabilityServiceImpl(
                commandService, dynamicDataService, permissionService, pageSchemaService);

        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setCode("crm:convert_lead");
        command.setDisplayName("Convert Lead");
        command.setModelCode("crm_lead_common");
        command.setType("custom");
        command.setExecutionConfig("{\"type\":\"custom\",\"preconditions\":[" +
                "{\"field\":\"crm_lead_status\",\"operator\":\"IN\"," +
                "\"value\":[\"qualified\"]}]}");

        when(commandService.listByModelCode("crm_lead_common")).thenReturn(List.of(command));
        when(pageSchemaService.findByModelCode("crm_lead_common")).thenReturn(List.of());

        when(dynamicDataService.getById("crm_lead_common", "qualified"))
                .thenReturn(Map.of("crm_lead_status", "qualified"));
        assertThat(service.getRecordCapabilities(
                "crm_lead_common", "qualified", "mobile", "detail", 1L).getCapabilities())
                .extracting("code")
                .containsExactly("crm:convert_lead");

        when(dynamicDataService.getById("crm_lead_common", "converted"))
                .thenReturn(Map.of("crm_lead_status", "converted"));
        assertThat(service.getRecordCapabilities(
                "crm_lead_common", "converted", "mobile", "detail", 1L).getCapabilities())
                .isEmpty();
    }

    @Test
    void mirrorsExecutionOperatorsAndDefersActionInputValidation() {
        CommandService commandService = mock(CommandService.class);
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        UserPermissionService permissionService = mock(UserPermissionService.class);
        PageSchemaService pageSchemaService = mock(PageSchemaService.class);
        RecordCapabilityServiceImpl service = new RecordCapabilityServiceImpl(
                commandService, dynamicDataService, permissionService, pageSchemaService);

        CommandDefinitionDTO win = stateCommand(
                "crm:win_opportunity",
                "[\"negotiation\"]",
                "closed_won",
                "[" +
                        "{\"field\":\"crm_opp_expected_amount\",\"operator\":\"GT\",\"value\":0}," +
                        "{\"field\":\"crm_opp_expected_close_date\",\"operator\":\"NOT_NULL\"}" +
                        "]",
                "[]");
        CommandDefinitionDTO lose = stateCommand(
                "crm:lose_opportunity",
                "[\"negotiation\"]",
                "closed_lost",
                "[" +
                        "{\"field\":\"crm_opp_lost_reason_code\",\"operator\":\"NEQ\",\"value\":\"\"}" +
                        "]",
                "[\"crm_opp_lost_reason_code\"]");

        when(commandService.listByModelCode("crm_opportunity_common")).thenReturn(List.of(win, lose));
        when(pageSchemaService.findByModelCode("crm_opportunity_common")).thenReturn(List.of());

        when(dynamicDataService.getById("crm_opportunity_common", "not-ready"))
                .thenReturn(Map.of(
                        "crm_opp_stage", "negotiation",
                        "crm_opp_expected_amount", 0));
        assertThat(service.getRecordCapabilities(
                "crm_opportunity_common", "not-ready", "web", "detail", 1L).getCapabilities())
                .extracting("code")
                .containsExactly("crm:lose_opportunity");

        when(dynamicDataService.getById("crm_opportunity_common", "ready"))
                .thenReturn(Map.of(
                        "crm_opp_stage", "negotiation",
                        "crm_opp_expected_amount", 120000,
                        "crm_opp_expected_close_date", "2026-12-31"));
        assertThat(service.getRecordCapabilities(
                "crm_opportunity_common", "ready", "web", "detail", 1L).getCapabilities())
                .extracting("code")
                .containsExactly("crm:lose_opportunity", "crm:win_opportunity");
    }

    private CommandDefinitionDTO stateCommand(String code, String fromStates, String toState,
                                               String preconditions, String inputFields) {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setCode(code);
        command.setDisplayName(code);
        command.setModelCode("crm_opportunity_common");
        command.setType("state_transition");
        command.setExecutionConfig("{" +
                "\"type\":\"state_transition\"," +
                "\"stateField\":\"crm_opp_stage\"," +
                "\"fromStates\":" + fromStates + "," +
                "\"toState\":\"" + toState + "\"," +
                "\"preconditions\":" + preconditions + "," +
                "\"inputFields\":" + inputFields +
                "}");
        return command;
    }
}
