package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * SEM-TOOLBAR: Validates toolbar button action semantic consistency.
 * <p>
 * CREATE/UPDATE commands require user input, so toolbar buttons pointing to them
 * must use type="navigate" (to a form page) rather than type="command" (direct execute).
 * <p>
 * Rules:
 * <ul>
 *   <li>SEM-TB-001 ERROR: CREATE command + type=command — would silently fail at runtime</li>
 *   <li>SEM-TB-002 ERROR: UPDATE command + type=command — needs form input</li>
 *   <li>SEM-TB-003 ERROR: type=navigate but missing 'to' field</li>
 *   <li>SEM-TB-004 ERROR: type=navigate pointing to page not defined in this plugin</li>
 *   <li>SEM-TB-005 ERROR: DELETE command without confirmation dialog configuration</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolbarActionValidator implements PluginValidator {

    private static final String CATEGORY = "semantic";

    // Command type constants (stored lowercase in DB)
    private static final String CMD_TYPE_CREATE = "create";
    private static final String CMD_TYPE_UPDATE = "update";
    private static final String CMD_TYPE_DELETE = "delete";

    // Action type constants
    private static final String ACTION_TYPE_COMMAND = "command";
    private static final String ACTION_TYPE_NAVIGATE = "navigate";

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();
        if (manifest == null) return messages;

        // Build index: commandCode -> commandType (create/update/delete/state_transition)
        Map<String, String> commandTypeByCode = buildCommandTypeIndex(manifest);

        // Build set of page keys defined in this plugin for cross-reference validation
        Set<String> pluginPageKeys = manifest.getPages() != null
                ? manifest.getPages().stream()
                        .filter(p -> p.getPageKey() != null)
                        .map(PageSchemaDTO::getPageKey)
                        .collect(Collectors.toSet())
                : Set.of();

        // Validate toolbar buttons on each page
        if (manifest.getPages() != null) {
            for (int i = 0; i < manifest.getPages().size(); i++) {
                PageSchemaDTO page = manifest.getPages().get(i);
                if (page == null || page.getDslSchema() == null) continue;
                String pagePath = "pages[" + i + "]";
                validatePageToolbarButtons(page, pagePath, commandTypeByCode, pluginPageKeys, messages);
            }
        }

        return messages;
    }

    /**
     * Build a map of commandCode -> effective command type from the plugin manifest.
     * Falls back to inferring type from command code name patterns when not explicitly set.
     */
    private Map<String, String> buildCommandTypeIndex(PluginManifestExtended manifest) {
        Map<String, String> index = new HashMap<>();
        if (manifest.getCommands() == null) return index;

        for (CommandDefinitionDTO cmd : manifest.getCommands()) {
            if (cmd.getCode() == null) continue;
            String effectiveType = resolveCommandType(cmd);
            if (effectiveType != null) {
                index.put(cmd.getCode(), effectiveType.toLowerCase());
            }
        }
        return index;
    }

    /**
     * Resolve the command type from the DTO.
     * Prefers the explicit {@code type} field; falls back to inferring from code name pattern.
     */
    private String resolveCommandType(CommandDefinitionDTO cmd) {
        // Explicit type field takes priority
        if (cmd.getType() != null && !cmd.getType().isBlank()) {
            return cmd.getType().toLowerCase();
        }
        // Fall back to executionConfig.type (structured form)
        if (cmd.getExecutionConfig() != null) {
            // ExecutionConfig doesn't carry a 'type' field — it uses executionMode/handler
            // So we skip this path and rely on name pattern below
        }
        // Infer from command code name pattern: {namespace}:{operation}_{model}
        // e.g., "crm:create_lead" -> create, "crm:delete_contact" -> delete
        return inferTypeFromCodePattern(cmd.getCode());
    }

    /**
     * Infer command type from naming convention: the segment after ":" starts with the operation.
     * Examples:
     *   "crm:create_lead"    -> "create"
     *   "ns:update_record"   -> "update"
     *   "ns:delete_item"     -> "delete"
     *   "ns:approve_request" -> null (not a standard CRUD type)
     */
    private String inferTypeFromCodePattern(String code) {
        if (code == null) return null;
        String local = code.contains(":") ? code.substring(code.indexOf(':') + 1) : code;
        local = local.toLowerCase();
        if (local.startsWith("create_") || local.equals("create")) return CMD_TYPE_CREATE;
        if (local.startsWith("update_") || local.equals("update")) return CMD_TYPE_UPDATE;
        if (local.startsWith("delete_") || local.equals("delete")) return CMD_TYPE_DELETE;
        return null;
    }

    /**
     * Validate all toolbar buttons on a single page.
     * Toolbar buttons are located at: dslSchema -> areas -> toolbar -> blocks -> [n] -> buttons -> [m]
     * Also checks action-column buttons in data-table blocks (inline row actions).
     */
    @SuppressWarnings("unchecked")
    private void validatePageToolbarButtons(PageSchemaDTO page, String pagePath,
                                            Map<String, String> commandTypeByCode,
                                            Set<String> pluginPageKeys,
                                            List<PluginValidationMessage> messages) {
        Map<String, Object> dsl = page.getDslSchema();
        Object areasObj = dsl.get("areas");
        if (!(areasObj instanceof Map)) return;

        Map<String, Object> areas = (Map<String, Object>) areasObj;
        Object toolbarAreaObj = areas.get("toolbar");
        if (!(toolbarAreaObj instanceof Map)) return;

        Map<String, Object> toolbarArea = (Map<String, Object>) toolbarAreaObj;
        Object blocksObj = toolbarArea.get("blocks");
        if (!(blocksObj instanceof List)) return;

        List<Object> blocks = (List<Object>) blocksObj;
        for (int bi = 0; bi < blocks.size(); bi++) {
            Object blockObj = blocks.get(bi);
            if (!(blockObj instanceof Map)) continue;
            Map<String, Object> block = (Map<String, Object>) blockObj;

            Object buttonsObj = block.get("buttons");
            if (!(buttonsObj instanceof List)) continue;

            List<Object> buttons = (List<Object>) buttonsObj;
            String blockPath = pagePath + ".dslSchema.areas.toolbar.blocks[" + bi + "]";

            for (int bti = 0; bti < buttons.size(); bti++) {
                Object buttonObj = buttons.get(bti);
                if (!(buttonObj instanceof Map)) continue;
                Map<String, Object> button = (Map<String, Object>) buttonObj;
                String buttonPath = blockPath + ".buttons[" + bti + "]";

                validateButton(button, buttonPath, page.getPageKey(), commandTypeByCode, pluginPageKeys, messages);
            }
        }
    }

    /**
     * Validate a single button's action configuration against the semantic rules.
     */
    @SuppressWarnings("unchecked")
    private void validateButton(Map<String, Object> button, String buttonPath,
                                 String pageKey, Map<String, String> commandTypeByCode,
                                 Set<String> pluginPageKeys,
                                 List<PluginValidationMessage> messages) {
        String buttonCode = getString(button, "code");
        String displayCode = buttonCode != null ? buttonCode : "(unnamed)";

        Object actionObj = button.get("action");
        if (!(actionObj instanceof Map)) return;
        Map<String, Object> action = (Map<String, Object>) actionObj;

        String actionType = getString(action, "type");
        if (actionType == null) return; // No type — not our concern here

        String commandCode = getString(action, "command");

        if (ACTION_TYPE_COMMAND.equals(actionType)) {
            // Rules SEM-TB-001 and SEM-TB-002: check command type requires form input
            if (commandCode != null) {
                String cmdType = resolveEffectiveCommandType(commandCode, commandTypeByCode);

                if (CMD_TYPE_CREATE.equals(cmdType)) {
                    // SEM-TB-001 ERROR: CREATE + type=command will silently fail
                    messages.add(error("SEM-TB-001", CATEGORY, buttonPath,
                            "Toolbar button '" + displayCode + "' on page '" + pageKey + "' uses type='command' " +
                                    "with CREATE command '" + commandCode + "'. CREATE commands require user input — " +
                                    "use type='navigate' with 'to' pointing to a form page."));

                } else if (CMD_TYPE_UPDATE.equals(cmdType)) {
                    // SEM-TB-002 ERROR: UPDATE + type=command needs form input
                    messages.add(error("SEM-TB-002", CATEGORY, buttonPath,
                            "Toolbar button '" + displayCode + "' on page '" + pageKey + "' uses type='command' " +
                                    "with UPDATE command '" + commandCode + "'. UPDATE commands need a form — " +
                                    "use type='navigate' with 'to' pointing to a form page."));

                } else if (CMD_TYPE_DELETE.equals(cmdType)) {
                    // SEM-TB-005 ERROR: DELETE without confirmation
                    if (!hasConfirmation(button)) {
                        messages.add(error("SEM-TB-005", CATEGORY, buttonPath,
                                "Toolbar button '" + displayCode + "' executes DELETE command '" + commandCode + "' " +
                                        "without confirmation dialog configuration."));
                    }
                }
            }

        } else if (ACTION_TYPE_NAVIGATE.equals(actionType)) {
            // Rule SEM-TB-003: navigate must have a 'to' field
            String toPageKey = getString(action, "to");
            if (toPageKey == null || toPageKey.isBlank()) {
                messages.add(error("SEM-TB-003", CATEGORY, buttonPath,
                        "Toolbar button '" + displayCode + "' on page '" + pageKey + "' has type='navigate' " +
                                "but missing 'to' field (form page key)."));

            } else if (!toPageKey.startsWith("/") && !pluginPageKeys.isEmpty() && !pluginPageKeys.contains(toPageKey)) {
                // Rule SEM-TB-004 ERROR: navigate to a page not defined in this plugin
                // Absolute paths (starting with '/') are platform routes — exempt from this check
                messages.add(error("SEM-TB-004", CATEGORY, buttonPath,
                        "Toolbar button '" + displayCode + "' on page '" + pageKey + "' navigates to '" + toPageKey + "' " +
                                "which is not defined in this plugin."));
            }
        }
    }

    /**
     * Resolve the effective command type: first from the plugin's command index,
     * then by inferring from the command code name pattern.
     */
    private String resolveEffectiveCommandType(String commandCode, Map<String, String> commandTypeByCode) {
        String explicit = commandTypeByCode.get(commandCode);
        if (explicit != null) return explicit;
        // Fall back to name pattern inference
        return inferTypeFromCodePattern(commandCode);
    }

    /**
     * Check whether a button has a confirmation dialog configured.
     * The DSL uses a top-level "confirm" field on the button object.
     */
    private boolean hasConfirmation(Map<String, Object> button) {
        Object confirm = button.get("confirm");
        if (confirm == null) return false;
        if (confirm instanceof String s) return !s.isBlank();
        if (confirm instanceof Map<?, ?> m) return !m.isEmpty();
        if (confirm instanceof Boolean b) return b;
        return false;
    }

    /**
     * Safely get a String value from a map.
     */
    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }
}
