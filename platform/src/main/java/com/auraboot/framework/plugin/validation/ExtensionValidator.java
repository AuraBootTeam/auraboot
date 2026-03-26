package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.registry.CommandHandlerRegistry;
import com.auraboot.framework.meta.registry.RenderComponentRegistry;
import com.auraboot.framework.meta.registry.SideEffectHandlerRegistry;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-EXT: Validates that DSL references to open registry entries
 * (command handlers, side effect types, render components) are actually registered.
 */
@Component
@RequiredArgsConstructor
public class ExtensionValidator implements PluginValidator {

    private final CommandHandlerRegistry commandHandlerRegistry;
    private final SideEffectHandlerRegistry sideEffectHandlerRegistry;
    private final RenderComponentRegistry renderComponentRegistry;

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        validateCommandHandlers(manifest, messages);
        validateSideEffectActions(manifest, messages);
        validateRenderComponents(manifest, messages);

        return messages;
    }

    /**
     * Check that command handler references point to registered handlers.
     */
    private void validateCommandHandlers(PluginManifestExtended manifest, List<PluginValidationMessage> messages) {
        if (manifest.getCommands() == null) return;

        for (int i = 0; i < manifest.getCommands().size(); i++) {
            CommandDefinitionDTO cmd = manifest.getCommands().get(i);
            if (cmd == null) continue;

            Map<String, Object> execConfig = cmd.getConsolidatedExecutionConfig();
            if (execConfig == null) continue;

            Object handler = execConfig.get("handler");
            if (handler != null) {
                String handlerCode = handler.toString();
                if (!handlerCode.isBlank() && !commandHandlerRegistry.isRegistered(handlerCode)) {
                    messages.add(error("S-EXT-HANDLER", category(),
                            "commands[" + i + "].handler",
                            "Command '" + cmd.getCode() + "' references unregistered handler: '" + handlerCode + "'"));
                }
            }
        }
    }

    /**
     * Check that side effect action types point to registered handlers.
     */
    @SuppressWarnings("unchecked")
    private void validateSideEffectActions(PluginManifestExtended manifest, List<PluginValidationMessage> messages) {
        if (manifest.getCommands() == null) return;

        for (int i = 0; i < manifest.getCommands().size(); i++) {
            CommandDefinitionDTO cmd = manifest.getCommands().get(i);
            if (cmd == null || cmd.getSideEffects() == null) continue;

            for (int j = 0; j < cmd.getSideEffects().size(); j++) {
                CommandDefinitionDTO.SideEffectConfig se = cmd.getSideEffects().get(j);
                if (se == null) continue;

                String path = "commands[" + i + "].sideEffects[" + j + "]";

                // Check top-level action
                if (se.getAction() != null && !se.getAction().isBlank()) {
                    if (!sideEffectHandlerRegistry.isRegistered(se.getAction())) {
                        messages.add(error("S-EXT-SIDEEFFECT", category(), path + ".action",
                                "Command '" + cmd.getCode() + "' sideEffect references unregistered action: '"
                                        + se.getAction() + "'"));
                    }
                }

                // Check nested actions list
                if (se.getActions() != null) {
                    for (int k = 0; k < se.getActions().size(); k++) {
                        Map<String, Object> actionMap = se.getActions().get(k);
                        if (actionMap == null) continue;

                        Object actionType = actionMap.get("action");
                        if (actionType != null && !actionType.toString().isBlank()) {
                            if (!sideEffectHandlerRegistry.isRegistered(actionType.toString())) {
                                messages.add(error("S-EXT-SIDEEFFECT", category(),
                                        path + ".actions[" + k + "].action",
                                        "Command '" + cmd.getCode() + "' sideEffect references unregistered action: '"
                                                + actionType + "'"));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check that field renderComponent references point to registered components.
     * Uses WARNING (not ERROR) because plugin frontend may provide the component.
     */
    private void validateRenderComponents(PluginManifestExtended manifest, List<PluginValidationMessage> messages) {
        if (manifest.getFields() == null) return;

        for (int i = 0; i < manifest.getFields().size(); i++) {
            FieldDefinitionDTO field = manifest.getFields().get(i);
            if (field == null || field.getExtension() == null) continue;

            Object renderComponent = field.getExtension().get("renderComponent");
            if (renderComponent != null) {
                String componentCode = renderComponent.toString();
                if (!componentCode.isBlank() && !renderComponentRegistry.isRegistered(componentCode)) {
                    messages.add(warning("S-EXT-RENDER", category(),
                            "fields[" + i + "].extension.renderComponent",
                            "Field '" + field.getCode() + "' references unregistered renderComponent: '"
                                    + componentCode + "' (may be provided by plugin frontend)"));
                }
            }

            // Also validate dataType ↔ renderComponent compatibility
            validateFieldTypeCompatibility(field, i, messages);
        }
    }

    /**
     * Validate that field dataType is compatible with the specified renderComponent.
     * Mirrors the TYPE_COMPATIBLE_COMPONENTS map from BindingValidator.ts.
     * Uses WARNING (not ERROR) because plugin frontend may support additional components.
     */
    private void validateFieldTypeCompatibility(FieldDefinitionDTO field, int index,
                                                List<PluginValidationMessage> messages) {
        if (field.getExtension() == null) return;
        Object renderComponentObj = field.getExtension().get("renderComponent");
        if (renderComponentObj == null) return;

        String dataType = field.getDataType();
        String renderComponent = renderComponentObj.toString();
        if (dataType == null || dataType.isBlank() || renderComponent.isBlank()) return;

        List<String> compatible = TYPE_COMPATIBLE_COMPONENTS.get(dataType.toUpperCase());
        if (compatible == null) return; // Unknown dataType — skip check

        boolean isCompatible = compatible.stream()
                .anyMatch(c -> c.equalsIgnoreCase(renderComponent));
        if (!isCompatible) {
            messages.add(warning("S-EXT-TYPE-COMPAT", category(),
                    "fields[" + index + "].extension.renderComponent",
                    "Field '" + field.getCode() + "' (dataType=" + dataType + ") uses renderComponent '"
                            + renderComponent + "' which may not be compatible. "
                            + "Expected one of: " + compatible));
        }
    }

    /**
     * dataType → compatible renderComponents mapping.
     * Mirrors BindingValidator.ts TYPE_COMPATIBLE_COMPONENTS.
     */
    private static final Map<String, List<String>> TYPE_COMPATIBLE_COMPONENTS = Map.ofEntries(
            Map.entry("string",   List.of("SmartInput", "SmartTextArea", "SmartSelect", "input", "textarea")),
            Map.entry("text",     List.of("SmartTextArea", "SmartRichText", "textarea")),
            Map.entry("integer",  List.of("SmartNumber", "SmartInput", "input")),
            Map.entry("decimal",  List.of("SmartNumber", "SmartInput", "input")),
            Map.entry("boolean",  List.of("SmartSwitch", "SmartCheckbox", "checkbox", "switch")),
            Map.entry("date",     List.of("SmartDatePicker", "SmartDateRangePicker", "date")),
            Map.entry("datetime", List.of("SmartDateTimePicker", "datetime")),
            Map.entry("enum",     List.of("SmartSelect", "SmartRadio", "SmartCheckboxGroup", "select", "radio")),
            Map.entry("ref",      List.of("SmartSelect", "SmartLookup", "SmartTreeSelect", "select")),
            Map.entry("file",     List.of("SmartUpload", "upload")),
            Map.entry("image",    List.of("SmartImageUpload", "SmartUpload", "upload"))
    );
}
