package com.auraboot.module.meta.excel;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelImportPolicyResolverTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private CommandService commandService;

    @Test
    void resolve_shouldBindCommandsFieldsDefaultsAndUpdateKey() {
        String modelCode = "crm_account_common";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(
                ModelDefinition.builder()
                        .code(modelCode)
                        .extension(Map.of("importPolicy", Map.of(
                                "enabled", true,
                                "modes", List.of("INSERT", "UPDATE"),
                                "updateKeys", List.of("crm_acc_code"))))
                        .build()));
        when(metaModelService.getModelFields(modelCode)).thenReturn(List.of(
                FieldDefinition.builder().code("crm_acc_code").build(),
                FieldDefinition.builder().code("crm_acc_name").build(),
                FieldDefinition.builder().code("crm_acc_status").build()));
        when(commandService.resolveCrudCommands(modelCode)).thenReturn(Map.of(
                "create", "crm:create_account",
                "update", "crm:update_account"));

        CommandDefinitionDTO create = new CommandDefinitionDTO();
        create.setExecutionConfig("""
                {"inputFields":["crm_acc_name","crm_acc_status"],
                 "autoSetFields":{"crm_acc_code":{"strategy":"auto_generate"},
                                  "crm_acc_status":{"strategy":"default_value","value":"active"}}}
                """);
        CommandDefinitionDTO update = new CommandDefinitionDTO();
        update.setExecutionConfig("{\"inputFields\":[\"crm_acc_name\",\"crm_acc_status\"]}");
        when(commandService.findByCode("crm:create_account")).thenReturn(create);
        when(commandService.findByCode("crm:update_account")).thenReturn(update);

        ExcelImportPolicy policy = new ExcelImportPolicyResolver(metaModelService, commandService)
                .requireEnabled(modelCode);

        assertEquals(java.util.Set.of("insert", "update"), policy.getModes());
        assertEquals(List.of("crm_acc_code"), policy.getUpdateKeys());
        assertEquals(java.util.Set.of("crm_acc_name", "crm_acc_status"), policy.getCreateFields());
        assertEquals(java.util.Set.of("crm_acc_code", "crm_acc_status"), policy.getCreateAutoSetFields());
        assertEquals("crm:update_account", policy.getUpdateCommand());
    }

    @Test
    void requireEnabled_shouldFailClosedWithoutExplicitPolicy() {
        when(metaModelService.getModelDefinition("unconfigured")).thenReturn(Optional.of(
                ModelDefinition.builder().code("unconfigured").build()));
        when(metaModelService.getModelFields("unconfigured")).thenReturn(List.of());
        when(commandService.resolveCrudCommands("unconfigured")).thenReturn(Map.of());

        ExcelImportPolicyResolver resolver = new ExcelImportPolicyResolver(metaModelService, commandService);

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.requireEnabled("unconfigured"));
        assertTrue(error.getMessage().contains("not enabled"));
    }

    @Test
    void validateMode_shouldRejectUnlistedUpdateKey() {
        ExcelImportPolicy policy = ExcelImportPolicy.builder()
                .enabled(true)
                .modes(java.util.Set.of("insert", "update"))
                .updateKeys(List.of("crm_acc_code"))
                .build();
        ExcelImportPolicyResolver resolver = new ExcelImportPolicyResolver(metaModelService, commandService);

        assertThrows(BusinessException.class,
                () -> resolver.validateMode(policy, "update", "crm_acc_name"));
    }

    @Test
    void resolve_shouldExcludeInfrastructureFieldsForPureCrudModels() {
        String modelCode = "simple_model";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(
                ModelDefinition.builder().code(modelCode).extension(Map.of(
                        "importPolicy", Map.of("enabled", true, "modes", List.of("insert"))))
                        .build()));
        when(metaModelService.getModelFields(modelCode)).thenReturn(List.of(
                FieldDefinition.builder().code("tenant_id").build(),
                FieldDefinition.builder().code("created_by").build(),
                FieldDefinition.builder().code("name").build()));
        when(commandService.resolveCrudCommands(modelCode)).thenReturn(Map.of());

        ExcelImportPolicy policy = new ExcelImportPolicyResolver(metaModelService, commandService)
                .requireEnabled(modelCode);

        assertEquals(java.util.Set.of("name"), policy.getCreateFields());
    }
}
