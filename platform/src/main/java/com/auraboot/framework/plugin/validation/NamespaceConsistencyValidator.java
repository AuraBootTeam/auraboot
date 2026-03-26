package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-NS: Validates that resource codes follow the plugin's namespace prefix convention.
 * <p>
 * Models should use "{namespace}_" prefix (e.g., "pe_customer" for namespace "pe").
 * Commands should use "{namespace}:" prefix (e.g., "pe:create-customer").
 * Exemption: models with tableName (binding to existing platform tables).
 */
@Component
public class NamespaceConsistencyValidator implements PluginValidator {

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();
        String ns = ctx.getNamespace();

        if (ns == null || ns.isBlank()) return messages;

        // Check model codes
        if (manifest.getModels() != null) {
            for (int i = 0; i < manifest.getModels().size(); i++) {
                ModelDefinitionDTO model = manifest.getModels().get(i);
                if (model == null || model.getCode() == null) continue;

                // Exempt models bound to existing tables
                if (isBoundToExistingTable(model)) continue;

                if (!model.getCode().startsWith(ns + "_")) {
                    messages.add(warning("S-NS-MODEL", category(),
                            "models[" + i + "].code",
                            "Model '" + model.getCode() + "' does not follow namespace prefix '" +
                                    ns + "_'"));
                }
            }
        }

        // Check command codes
        if (manifest.getCommands() != null) {
            for (int i = 0; i < manifest.getCommands().size(); i++) {
                CommandDefinitionDTO cmd = manifest.getCommands().get(i);
                if (cmd == null || cmd.getCode() == null) continue;

                if (!cmd.getCode().startsWith(ns + ":")) {
                    messages.add(warning("S-NS-COMMAND", category(),
                            "commands[" + i + "].code",
                            "Command '" + cmd.getCode() + "' does not follow namespace prefix '" +
                                    ns + ":'"));
                }
            }
        }

        return messages;
    }

    private boolean isBoundToExistingTable(ModelDefinitionDTO model) {
        return model.getTableName() != null && !model.getTableName().isBlank();
    }
}
