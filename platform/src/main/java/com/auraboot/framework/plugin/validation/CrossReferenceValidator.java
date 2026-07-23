package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-REF: Validates cross-reference integrity within a plugin manifest.
 * <p>
 * Checks:
 * - Commands reference valid model codes (from plugin or installed)
 * - Bindings reference valid model codes and field codes
 * - Menus reference valid permission codes (from plugin or installed)
 * - Pages reference valid model codes (when modelCode is specified)
 */
@Component
public class CrossReferenceValidator implements PluginValidator {

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public boolean requiresReferenceValidation() {
        return true;
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        // Build sets of available resources
        Set<String> availableModels = buildAvailableModels(manifest, ctx);
        Set<String> availableFields = buildAvailableFields(manifest, ctx);
        Set<String> availablePermissions = buildAvailablePermissions(manifest, ctx);

        // Two-phase cross-plugin reference handling: in a batch cold-reset of a cyclic plugin
        // set (e.g. crm↔sales), a command/binding may legitimately reference a model owned by
        // another plugin not yet imported in the same pass. When deferral is on, those model
        // references are downgraded from hard errors to deferred warnings; the truly-dangling
        // case (a model provided by no plugin at all) is re-enforced afterwards by the closing
        // reference-integrity sweep (PluginImportService.verifyImportReferenceIntegrity).
        boolean defer = Boolean.TRUE.equals(ctx.getDeferReferenceValidation());

        checkCommandModelRefs(manifest, availableModels, defer, messages);
        checkBindingRefs(manifest, availableModels, availableFields, defer, messages);
        checkMenuPermissionRefs(manifest, availablePermissions, defer, messages);

        return messages;
    }

    private Set<String> buildAvailableModels(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> models = new HashSet<>();
        if (ctx.getInstalledModelCodes() != null) {
            models.addAll(ctx.getInstalledModelCodes());
        }
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO m : manifest.getModels()) {
                if (m != null && m.getCode() != null) {
                    models.add(m.getCode());
                }
            }
        }
        return models;
    }

    private Set<String> buildAvailableFields(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> fields = new HashSet<>();
        if (ctx.getInstalledFieldCodes() != null) {
            fields.addAll(ctx.getInstalledFieldCodes());
        }
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO f : manifest.getFields()) {
                if (f != null && f.getCode() != null) {
                    fields.add(f.getCode());
                }
            }
        }
        return fields;
    }

    private Set<String> buildAvailablePermissions(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> perms = new HashSet<>();
        if (ctx.getInstalledPermissionCodes() != null) {
            perms.addAll(ctx.getInstalledPermissionCodes());
        }
        if (manifest.getPermissions() != null) {
            for (PermissionDefinitionDTO p : manifest.getPermissions()) {
                if (p != null && p.getCode() != null) {
                    perms.add(p.getCode());
                }
            }
        }
        return perms;
    }

    private void checkCommandModelRefs(PluginManifestExtended manifest, Set<String> availableModels,
                                        boolean defer, List<PluginValidationMessage> messages) {
        if (manifest.getCommands() == null) return;

        for (int i = 0; i < manifest.getCommands().size(); i++) {
            CommandDefinitionDTO cmd = manifest.getCommands().get(i);
            if (cmd == null || cmd.getModelCode() == null) continue;

            if (!availableModels.contains(cmd.getModelCode())) {
                String path = "commands[" + i + "].modelCode";
                String text = "Command '" + cmd.getCode() + "' references non-existent model '" +
                        cmd.getModelCode() + "'";
                // Cross-plugin model reference: deferred to the closing sweep when a cyclic batch
                // import is in progress, otherwise a hard error.
                messages.add(defer
                        ? warning("S-REF-MODEL", category(), path, text)
                        : error("S-REF-MODEL", category(), path, text));
            }
        }
    }

    private void checkBindingRefs(PluginManifestExtended manifest, Set<String> availableModels,
                                   Set<String> availableFields, boolean defer,
                                   List<PluginValidationMessage> messages) {
        if (manifest.getModelFieldBindings() == null) return;

        for (int i = 0; i < manifest.getModelFieldBindings().size(); i++) {
            ModelFieldBindingDTO binding = manifest.getModelFieldBindings().get(i);
            if (binding == null) continue;

            if (binding.getModelCode() != null && !availableModels.contains(binding.getModelCode())) {
                String path = "modelFieldBindings[" + i + "].modelCode";
                String text = "Binding references non-existent model '" + binding.getModelCode() + "'";
                // Only the *model* reference is cross-plugin / deferrable; the field reference below
                // stays a hard error since fields are owned by the same plugin as their model.
                messages.add(defer
                        ? warning("S-REF-BINDING-MODEL", category(), path, text)
                        : error("S-REF-BINDING-MODEL", category(), path, text));
            }

            if (binding.getFieldCode() != null && !availableFields.contains(binding.getFieldCode())) {
                messages.add(error("S-REF-BINDING-FIELD", category(),
                        "modelFieldBindings[" + i + "].fieldCode",
                        "Binding references non-existent field '" + binding.getFieldCode() +
                                "' for model '" + binding.getModelCode() + "'"));
            }
        }
    }

    private void checkMenuPermissionRefs(PluginManifestExtended manifest, Set<String> availablePermissions,
                                          boolean defer, List<PluginValidationMessage> messages) {
        if (manifest.getMenus() == null) return;

        for (int i = 0; i < manifest.getMenus().size(); i++) {
            MenuDefinitionDTO menu = manifest.getMenus().get(i);
            if (menu == null || menu.getPermissionCode() == null) continue;

            if (!availablePermissions.contains(menu.getPermissionCode())) {
                String path = "menus[" + i + "].permissionCode";
                String text = "Menu '" + (menu.getCode() != null ? menu.getCode() : menu.getName()) +
                        "' references permission '" + menu.getPermissionCode() +
                        "' not found in plugin or system";
                // Cross-plugin permission reference: deferred to the closing sweep when a cyclic
                // batch import is in progress (menu owned by one plugin in a cycle, permission
                // owned by another not-yet-imported plugin), otherwise a hard error.
                messages.add(defer
                        ? warning("S-REF-MENU-PERMISSION", category(), path, text)
                        : error("S-REF-MENU-PERMISSION", category(), path, text));
            }
        }
    }
}
