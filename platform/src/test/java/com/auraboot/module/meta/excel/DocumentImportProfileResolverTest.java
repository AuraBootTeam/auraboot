package com.auraboot.module.meta.excel;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentImportProfileResolverTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private CommandService commandService;

    @Test
    void requireEnabledResolvesADeclaredAggregateProfile() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model(modelCode,
                documentPolicy(List.of("order_no", "supplier"), List.of("order_no", "sku", "qty")))));
        declareFields();
        when(commandService.findByCode("purchase:create_order_with_lines"))
                .thenReturn(command(modelCode, "create"));

        DocumentImportProfile profile = new DocumentImportProfileResolver(metaModelService, commandService)
                .requireEnabled(modelCode);

        assertEquals("purchase-order-standard", profile.getCode());
        assertEquals("purchase:create_order_with_lines", profile.getCommand());
        assertEquals("order_no", profile.getGroupBy());
        assertEquals("order_no", profile.getLineGroupField());
        assertEquals("lines", profile.getLinesPayloadField());
        assertTrue(profile.isRequireLines());
    }

    @Test
    void requireEnabledRejectsMissingAggregateCommand() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model(modelCode,
                documentPolicy(List.of("order_no"), List.of("order_no", "sku")))));
        when(metaModelService.getModelFields(modelCode)).thenReturn(List.of(field("order_no")));
        when(metaModelService.getModelFields("purchase_order_line"))
                .thenReturn(List.of(field("order_no"), field("sku")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new DocumentImportProfileResolver(metaModelService, commandService)
                        .requireEnabled(modelCode));

        assertTrue(error.getMessage().contains("command not found"));
    }

    @Test
    void requireEnabledRejectsProfilesWithoutBothGroupingFields() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model(modelCode,
                documentPolicy(List.of("supplier"), List.of("sku", "qty")))));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new DocumentImportProfileResolver(metaModelService, commandService)
                        .requireEnabled(modelCode));

        assertTrue(error.getMessage().contains("grouping fields"));
    }

    @Test
    void requireEnabledRejectsRowProfiles() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(ModelDefinition.builder()
                .code(modelCode)
                .extension(Map.of("importPolicy", Map.of("enabled", true, "atomicUnit", "row")))
                .build()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new DocumentImportProfileResolver(metaModelService, commandService)
                        .requireEnabled(modelCode));

        assertTrue(error.getMessage().contains("not enabled"));
    }

    @Test
    void requireEnabledRejectsCommandForAnotherModel() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model(modelCode,
                documentPolicy(List.of("order_no", "supplier"), List.of("order_no", "sku", "qty")))));
        declareFields();
        when(commandService.findByCode("purchase:create_order_with_lines"))
                .thenReturn(command("other_order", "create"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new DocumentImportProfileResolver(metaModelService, commandService)
                        .requireEnabled(modelCode));

        assertTrue(error.getMessage().contains("create command for model"));
    }

    @Test
    void requireEnabledRejectsUnknownWhitelistedField() {
        String modelCode = "purchase_order";
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model(modelCode,
                documentPolicy(List.of("order_no", "unknown"), List.of("order_no", "sku", "qty")))));
        when(metaModelService.getModelFields("purchase_order"))
                .thenReturn(List.of(field("order_no"), field("supplier")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> new DocumentImportProfileResolver(metaModelService, commandService)
                        .requireEnabled(modelCode));

        assertTrue(error.getMessage().contains("Unknown document import fields"));
    }

    private void declareFields() {
        when(metaModelService.getModelFields("purchase_order"))
                .thenReturn(List.of(field("order_no"), field("supplier")));
        when(metaModelService.getModelFields("purchase_order_line"))
                .thenReturn(List.of(field("order_no"), field("sku"), field("qty")));
    }

    private static FieldDefinition field(String code) {
        return FieldDefinition.builder().code(code).build();
    }

    private static CommandDefinitionDTO command(String modelCode, String type) {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setModelCode(modelCode);
        command.setType(type);
        return command;
    }

    private static ModelDefinition model(String modelCode, Map<String, Object> policy) {
        return ModelDefinition.builder()
                .code(modelCode)
                .extension(Map.of("importPolicy", policy))
                .build();
    }

    private static Map<String, Object> documentPolicy(List<String> headerFields, List<String> lineFields) {
        return Map.of(
                "enabled", true,
                "code", "purchase-order-standard",
                "atomicUnit", "document",
                "document", Map.of(
                        "command", "purchase:create_order_with_lines",
                        "headerSheet", "Orders",
                        "lineSheet", "Lines",
                        "groupBy", "order_no",
                        "lineGroupField", "order_no",
                        "lineModelCode", "purchase_order_line",
                        "linesPayloadField", "lines",
                        "headerFields", headerFields,
                        "lineFields", lineFields));
    }
}
