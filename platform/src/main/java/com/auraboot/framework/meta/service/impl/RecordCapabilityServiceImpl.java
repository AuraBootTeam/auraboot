package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.RecordCapabilities;
import com.auraboot.framework.meta.dto.RecordCapabilities.ActionCapability;
import com.auraboot.framework.meta.dto.RecordCapabilities.FormSchema;
import com.auraboot.framework.meta.dto.RecordCapabilities.TabCapability;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.RecordCapabilityService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ARCH-001: Record-context Capability API implementation.
 * <p>
 * Implements the Capability Resolver Pipeline as defined in
 * {@code docs/system-reference/subsystems/50-Capability动作能力接口.md}:
 * <ol>
 *   <li>Load all commands for the model</li>
 *   <li>State Filter: check record state vs. command fromStates</li>
 *   <li>Permission Filter: check user permissions</li>
 *   <li>Platform Filter: check visibility.platforms</li>
 *   <li>Context Filter: filter by usage context (detail/list/inbox)</li>
 *   <li>Priority Sort: ascending</li>
 *   <li>showInActionBar: mark top N for sticky action bar</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordCapabilityServiceImpl implements RecordCapabilityService {

    private final CommandService commandService;
    private final DynamicDataService dynamicDataService;
    private final UserPermissionService userPermissionService;
    private final PageSchemaService pageSchemaService;

    /** Command types that are meaningful as record-context actions (exclude query/create). */
    private static final Set<String> CONTEXTUAL_TYPES = Set.of(
            "state_transition", "update", "delete", "action", "custom"
    );

    /** Command types that represent destructive operations. */
    private static final Set<String> DANGER_TYPES = Set.of("delete");

    /** Max actions shown in the action bar by context. */
    private static final Map<String, Integer> ACTION_BAR_LIMITS = Map.of(
            "detail", 2,
            "list", 1,
            "inbox", 2
    );

    /** Priority ceiling for actions returned in list context. */
    private static final int LIST_CONTEXT_MAX_PRIORITY = 30;

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF =
            new TypeReference<>() {};

    @Override
    public RecordCapabilities getRecordCapabilities(String modelCode, String recordId,
                                                    String platform, String context,
                                                    Long userId) {
        log.debug("Resolving record capabilities: model={}, record={}, platform={}, context={}, user={}",
                modelCode, recordId, platform, context, userId);

        String resolvedContext = normalizeContext(context);
        String resolvedPlatform = normalizePlatform(platform);

        // 1. Load the record to get current state
        Map<String, Object> record = loadRecordSafely(modelCode, recordId);

        // 2. Load all commands for this model
        List<CommandDefinitionDTO> allCommands = commandService.listByModelCode(modelCode);

        // 3. Pipeline: filter → sort
        List<ActionCapability> actions = allCommands.stream()
                .map(cmd -> toActionCapability(cmd, record, resolvedPlatform, userId))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ActionCapability::getPriority)
                        .thenComparing(ActionCapability::getCode))
                .collect(Collectors.toList());

        // 4. Context filter
        actions = applyContextFilter(actions, resolvedContext);

        // 5. Mark showInActionBar for top N non-destructive actions
        markActionBarVisibility(actions, resolvedContext);

        // 6. Derive record state
        String recordState = extractRecordState(record, allCommands);

        // 7. Derive tabs from detail page schema
        List<TabCapability> tabs = deriveTabs(modelCode, resolvedContext);

        // 8. Generate ETag
        String etag = generateETag(recordId, record);

        return RecordCapabilities.builder()
                .modelCode(modelCode)
                .recordId(recordId)
                .recordState(recordState)
                .capabilities(actions)
                .tabs(tabs)
                .etag(etag)
                .build();
    }

    // ==================== Pipeline Stage: Transform + Filter ====================

    /**
     * Transform a command definition to an ActionCapability. Returns null if the
     * command should be excluded (wrong type, failed permission, wrong state, wrong platform).
     */
    private ActionCapability toActionCapability(CommandDefinitionDTO cmd,
                                                Map<String, Object> record,
                                                String platform,
                                                Long userId) {
        Map<String, Object> execConfig = parseJson(cmd.getExecutionConfig());
        String cmdType = resolveCmdType(cmd, execConfig);

        // Filter: only contextual action types
        if (cmdType == null || !CONTEXTUAL_TYPES.contains(cmdType)) {
            return null;
        }

        // Filter: user permissions (OR logic)
        if (!checkPermissions(execConfig, userId)) {
            return null;
        }

        // Filter: state_transition commands must match current record state
        if ("state_transition".equals(cmdType) && !checkStateTransitionAllowed(execConfig, record)) {
            return null;
        }

        // Filter: platform
        if (!checkPlatformAllowed(execConfig, platform)) {
            return null;
        }

        // Derive properties
        String executionMode = deriveExecutionMode(cmdType, execConfig);
        String style = deriveStyle(cmdType, execConfig);
        int priority = derivePriority(cmdType, execConfig);
        String icon = deriveIcon(cmdType, execConfig);
        String targetState = "state_transition".equals(cmdType) ? extractString(execConfig, "toState") : null;
        String confirmMessage = deriveConfirmMessage(cmdType, execConfig, cmd);
        FormSchema formSchema = deriveFormSchema(cmdType, execConfig, cmd);
        boolean destructive = DANGER_TYPES.contains(cmdType) || "danger".equals(style);

        return ActionCapability.builder()
                .code(cmd.getCode())
                .label(cmd.getDisplayName() != null ? cmd.getDisplayName() : cmd.getCode())
                .type(mapToCapabilityType(cmdType))
                .icon(icon)
                .style(style)
                .executionMode(executionMode)
                .priority(priority)
                .showInActionBar(false) // set later by markActionBarVisibility
                .destructive(destructive)
                .confirmMessage(confirmMessage)
                .targetState(targetState)
                .formSchema(formSchema)
                .requiresNetwork(true)
                .commandCode(cmd.getCode())
                .build();
    }

    private String resolveCmdType(CommandDefinitionDTO cmd, Map<String, Object> execConfig) {
        String configType = extractString(execConfig, "type");
        return configType != null ? configType : cmd.getType();
    }

    private boolean checkPermissions(Map<String, Object> execConfig, Long userId) {
        if (userId == null) {
            return false;
        }
        Object permsObj = execConfig.get("permissions");
        if (permsObj instanceof List<?> permsList) {
            if (permsList.isEmpty()) return true;
            for (Object perm : permsList) {
                if (perm instanceof String permCode
                        && userPermissionService.hasPermission(userId, permCode)) {
                    return true;
                }
            }
            return false;
        }
        return true; // no permissions specified = allow
    }

    private boolean checkStateTransitionAllowed(Map<String, Object> execConfig,
                                                Map<String, Object> record) {
        if (record == null) return false;
        String stateField = extractString(execConfig, "stateField");
        if (stateField == null) return true;

        Object currentStateObj = record.get(stateField);
        if (currentStateObj == null) return false;
        String currentState = currentStateObj.toString().toLowerCase();

        Object fromStatesObj = execConfig.get("fromStates");
        if (fromStatesObj instanceof List<?> fromStates) {
            return fromStates.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.toString().toLowerCase())
                    .anyMatch(currentState::equals);
        }
        return true; // no fromStates restriction = allow
    }

    private boolean checkPlatformAllowed(Map<String, Object> execConfig, String platform) {
        Object platformsObj = execConfig.get("platforms");
        if (platformsObj instanceof List<?> platforms) {
            if (platforms.isEmpty()) return true;
            return platforms.stream()
                    .filter(Objects::nonNull)
                    .map(p -> p.toString().toLowerCase())
                    .anyMatch(p -> p.equals(platform) || p.equals("all"));
        }
        return true; // no restriction = all platforms
    }

    // ==================== Pipeline Stage: Context Filter ====================

    private List<ActionCapability> applyContextFilter(List<ActionCapability> actions, String context) {
        if ("list".equals(context)) {
            return actions.stream()
                    .filter(a -> a.getPriority() <= LIST_CONTEXT_MAX_PRIORITY)
                    .collect(Collectors.toList());
        }
        return actions;
    }

    // ==================== Pipeline Stage: Action Bar Visibility ====================

    private void markActionBarVisibility(List<ActionCapability> actions, String context) {
        int limit = ACTION_BAR_LIMITS.getOrDefault(context, 2);
        int count = 0;
        for (ActionCapability action : actions) {
            if (count < limit && !action.isDestructive()) {
                action.setShowInActionBar(true);
                count++;
            } else {
                action.setShowInActionBar(false);
            }
        }
    }

    // ==================== Property Derivation ====================

    /** Map internal command types to the spec's ActionType vocabulary. */
    private String mapToCapabilityType(String cmdType) {
        return switch (cmdType) {
            case "state_transition" -> "state_transition";
            case "update" -> "edit_field";
            case "delete" -> "destructive";
            case "action" -> "navigate";
            case "custom" -> "workflow_trigger";
            default -> cmdType;
        };
    }

    private String deriveExecutionMode(String cmdType, Map<String, Object> execConfig) {
        String explicit = extractString(execConfig, "executionMode");
        if (explicit != null) return explicit;
        return switch (cmdType) {
            case "state_transition" -> "confirm_dialog";
            case "delete" -> "confirm_dialog";
            case "update" -> "form_page";
            case "action" -> "immediate";
            case "custom" -> "form_page";
            default -> "confirm_dialog";
        };
    }

    private String deriveStyle(String cmdType, Map<String, Object> execConfig) {
        String explicit = extractString(execConfig, "style");
        if (explicit != null) return explicit;
        if (DANGER_TYPES.contains(cmdType)) return "danger";
        if ("state_transition".equals(cmdType)) return "primary";
        return "secondary";
    }

    private int derivePriority(String cmdType, Map<String, Object> execConfig) {
        Object explicit = execConfig.get("priority");
        if (explicit instanceof Number num) return num.intValue();
        return switch (cmdType) {
            case "state_transition" -> 1;
            case "action" -> 2;
            case "custom" -> 3;
            case "update" -> 10;
            case "delete" -> 99;
            default -> 50;
        };
    }

    private String deriveIcon(String cmdType, Map<String, Object> execConfig) {
        String explicit = extractString(execConfig, "icon");
        if (explicit != null) return explicit;
        return switch (cmdType) {
            case "state_transition" -> "arrow_forward";
            case "update" -> "edit";
            case "delete" -> "delete";
            case "action" -> "play_arrow";
            case "custom" -> "settings";
            default -> null;
        };
    }

    private String deriveConfirmMessage(String cmdType, Map<String, Object> execConfig,
                                        CommandDefinitionDTO cmd) {
        String explicit = extractString(execConfig, "confirmMessage");
        if (explicit != null) return explicit;

        if ("state_transition".equals(cmdType)) {
            String label = cmd.getDisplayName() != null ? cmd.getDisplayName() : cmd.getCode();
            return "Confirm " + label + "?";
        }
        if ("delete".equals(cmdType)) {
            return "Are you sure you want to delete this record? This action cannot be undone.";
        }
        return null;
    }

    /**
     * Build form schema for update/custom commands that define inputFields.
     */
    private FormSchema deriveFormSchema(String cmdType, Map<String, Object> execConfig,
                                        CommandDefinitionDTO cmd) {
        if (!"update".equals(cmdType) && !"custom".equals(cmdType)) return null;

        Object inputFieldsObj = execConfig.get("inputFields");
        if (!(inputFieldsObj instanceof List<?> fieldsList) || fieldsList.isEmpty()) return null;

        List<String> fields = fieldsList.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toList());

        return FormSchema.builder()
                .modelCode(cmd.getModelCode())
                .fields(fields)
                .build();
    }

    // ==================== Record State ====================

    /**
     * Extract the record's current state by first checking stateField from commands,
     * then falling back to common field names.
     */
    private String extractRecordState(Map<String, Object> record,
                                       List<CommandDefinitionDTO> commands) {
        if (record == null) return null;

        // Try stateField from any state_transition command
        for (CommandDefinitionDTO cmd : commands) {
            Map<String, Object> ec = parseJson(cmd.getExecutionConfig());
            if ("state_transition".equals(extractString(ec, "type"))) {
                String stateField = extractString(ec, "stateField");
                if (stateField != null) {
                    Object val = record.get(stateField);
                    if (val != null) return val.toString().toLowerCase();
                }
            }
        }
        // Fallback
        for (String field : List.of("status", "state", "stage")) {
            Object val = record.get(field);
            if (val != null) return val.toString().toLowerCase();
        }
        return null;
    }

    // ==================== Tab Derivation ====================

    /**
     * Derive tabs from the model's detail page DSL schema.
     * Only returns tabs for detail context; returns empty list for list/inbox.
     */
    private List<TabCapability> deriveTabs(String modelCode, String context) {
        if (!"detail".equals(context)) {
            return Collections.emptyList();
        }

        List<PageSchemaDTO> pages = pageSchemaService.findByModelCode(modelCode);
        PageSchemaDTO detailPage = pages.stream()
                .filter(p -> "detail".equals(p.getKind()))
                .findFirst()
                .orElse(null);

        if (detailPage != null && detailPage.getBlocks() != null) {
            // V2: blocks are top-level; wrap in areas.main.blocks for extractTabsFromSchema
            Map<String, Object> schemaMap = Map.of(
                    "areas", Map.of("main", Map.of("blocks", detailPage.getBlocks())));
            List<TabCapability> tabs = extractTabsFromSchema(schemaMap);
            if (!tabs.isEmpty()) return tabs;
        }

        // Default tabs when no detail page schema exists
        return List.of(
                TabCapability.builder().code("overview").label("Overview").visible(true).badge(0).build(),
                TabCapability.builder().code("activity").label("Activity").visible(true).badge(0).build(),
                TabCapability.builder().code("related").label("Related").visible(true).badge(0).build(),
                TabCapability.builder().code("discussion").label("Discussion").visible(true).badge(0).build()
        );
    }

    /**
     * Walk the detail page DSL schema to extract tab definitions.
     * Schema structure: {@code areas.main.blocks[].tabs[{key, label}]}.
     */
    @SuppressWarnings("unchecked")
    private List<TabCapability> extractTabsFromSchema(Map<String, Object> dslSchema) {
        List<TabCapability> result = new ArrayList<>();
        try {
            Map<String, Object> areas = asMap(dslSchema.get("areas"));
            if (areas == null) return result;
            Map<String, Object> main = asMap(areas.get("main"));
            if (main == null) return result;
            List<?> blocks = asList(main.get("blocks"));
            if (blocks == null) return result;

            for (Object blockObj : blocks) {
                Map<String, Object> block = asMap(blockObj);
                if (block == null) continue;
                List<?> tabs = asList(block.get("tabs"));
                if (tabs == null) continue;

                for (Object tabObj : tabs) {
                    Map<String, Object> tab = asMap(tabObj);
                    if (tab == null) continue;
                    String key = tab.get("key") != null ? tab.get("key").toString() : null;
                    if (key == null) continue;
                    String label = resolveLabel(tab.get("label"));
                    result.add(TabCapability.builder()
                            .code(key)
                            .label(label != null ? label : key)
                            .visible(true)
                            .badge(0)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract tabs from schema: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Resolve a label from a plain string or an i18n map {@code {"en-US":"…","zh-CN":"…"}}.
     */
    private String resolveLabel(Object labelObj) {
        if (labelObj == null) return null;
        if (labelObj instanceof String s) return s;
        if (labelObj instanceof Map<?, ?> map) {
            Object en = map.get("en-US");
            if (en != null) return en.toString();
            Object zh = map.get("zh-CN");
            if (zh != null) return zh.toString();
            return map.values().stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .findFirst()
                    .orElse(null);
        }
        return labelObj.toString();
    }

    // ==================== ETag ====================

    private String generateETag(String recordId, Map<String, Object> record) {
        long ts = System.currentTimeMillis() / 1000;
        if (record != null) {
            Object updatedAt = record.get("updated_at");
            if (updatedAt != null) {
                ts = Math.abs(Objects.hashCode(updatedAt));
            }
        }
        return "W/\"cap-" + recordId + "-" + ts + "\"";
    }

    // ==================== Helpers ====================

    private String normalizeContext(String context) {
        if (context == null || context.isBlank()) return "detail";
        String lower = context.toLowerCase();
        return Set.of("detail", "list", "inbox").contains(lower) ? lower : "detail";
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) return "web";
        return platform.toLowerCase();
    }

    private Map<String, Object> loadRecordSafely(String modelCode, String recordId) {
        try {
            return dynamicDataService.getById(modelCode, recordId);
        } catch (Exception e) {
            log.debug("Could not load record {}/{}: {}", modelCode, recordId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return JsonUtil.parse(json, MAP_TYPE_REF);
        } catch (Exception e) {
            log.debug("Failed to parse JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    private List<?> asList(Object obj) {
        return obj instanceof List ? (List<?>) obj : null;
    }
}
