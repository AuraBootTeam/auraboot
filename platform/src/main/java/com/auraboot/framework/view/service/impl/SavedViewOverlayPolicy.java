package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaRuntimeDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.entity.ViewConfig.ColumnConfig;
import com.auraboot.framework.view.entity.ViewConfig.Meta;
import com.auraboot.framework.view.entity.ViewConfig.ToolbarActionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Replays SavedView presentation overlays against the current immutable page baseline.
 *
 * <p>The policy never mutates PageSchema or creates an authoring ChangeSet. Invalid references
 * are ignored on reads so the page can still load, while new writes fail closed. Explicitly
 * mandatory fields and actions are restored on reads and cannot be hidden on writes.
 */
@Component
public class SavedViewOverlayPolicy {

    static final String STATUS_CURRENT = "CURRENT";
    static final String STATUS_REBASED = "REBASED";
    static final String STATUS_STALE = "STALE";
    static final String STATUS_UNTRACKED = "UNTRACKED";

    static final String REASON_BASE_PAGE_UNAVAILABLE = "BASE_PAGE_UNAVAILABLE";
    static final String REASON_LEGACY_NO_LINEAGE = "LEGACY_OVERLAY_NO_LINEAGE";
    static final String REASON_BASE_RELEASE_CHANGED = "BASE_RELEASE_CHANGED";
    static final String REASON_PAGE_IDENTITY_CHANGED = "PAGE_IDENTITY_CHANGED";
    static final String REASON_FIELD_REMOVED = "FIELD_REMOVED";
    static final String REASON_ACTION_REMOVED = "ACTION_REMOVED";
    static final String REASON_MANDATORY_RESTORED = "MANDATORY_ELEMENT_RESTORED";

    private static final int MAX_STALE_PATHS = 100;

    private final PageSchemaService pageSchemaService;
    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    public SavedViewOverlayPolicy(
            PageSchemaService pageSchemaService,
            MetaModelService metaModelService,
            ObjectMapper objectMapper) {
        this.pageSchemaService = pageSchemaService;
        this.metaModelService = metaModelService;
        this.objectMapper = objectMapper;
    }

    /** Validate a client write against the current page and stamp server-owned lineage. */
    public ViewConfig validateAndStamp(String pageKey, ViewConfig requested) {
        ViewConfig config = copy(requested);
        sanitizeNullOverlayItems(config);
        if (!StringUtils.hasText(pageKey)) {
            return config;
        }
        PageSchemaDTO page = requireRuntimePage(pageKey);
        SchemaFacts facts = schemaFacts(page);
        rejectUnknownReferences(config, facts);
        rejectMandatoryHiding(config, facts);
        stampCurrent(config, page, facts);
        return config;
    }

    /**
     * Produce an effective read view without modifying the stored overlay.
     *
     * <p>Removed references are discarded from the returned copy and described in metadata.
     */
    public ViewConfig replay(String pageKey, ViewConfig stored) {
        ViewConfig effective = copy(stored);
        sanitizeNullOverlayItems(effective);
        if (!StringUtils.hasText(pageKey)) {
            return effective;
        }
        PageSchemaDTO page;
        try {
            page = pageSchemaService.findByPageKey(pageKey);
        } catch (RuntimeException failure) {
            return markUnavailable(effective);
        }
        if (page == null) {
            return markUnavailable(effective);
        }

        SchemaFacts facts = schemaFacts(page);
        Meta storedMeta = effective.getMeta();
        if (storedMeta == null || !StringUtils.hasText(storedMeta.getBasePagePid())) {
            Set<String> reasons = new LinkedHashSet<>();
            List<String> stalePaths = new ArrayList<>();
            restoreMandatoryVisibility(effective, facts, reasons, stalePaths);
            Meta meta = ensureMeta(effective);
            meta.setOverlayStatus(stalePaths.isEmpty() ? STATUS_UNTRACKED : STATUS_STALE);
            reasons.add(REASON_LEGACY_NO_LINEAGE);
            meta.setOverlayReasonCodes(List.copyOf(reasons));
            meta.setOverlayStalePaths(List.copyOf(stalePaths));
            return effective;
        }

        Set<String> reasons = new LinkedHashSet<>();
        List<String> stalePaths = new ArrayList<>();
        if (!storedMeta.getBasePagePid().equals(page.getPid())) {
            reasons.add(REASON_PAGE_IDENTITY_CHANGED);
        }

        Set<String> removedFields = removed(
                storedMeta.getBaseFieldCodes(), facts.fieldCodes());
        Set<String> removedActions = removed(
                storedMeta.getBaseActionCodes(), facts.actionCodes());
        if (!removedFields.isEmpty()) {
            reasons.add(REASON_FIELD_REMOVED);
        }
        if (!removedActions.isEmpty()) {
            reasons.add(REASON_ACTION_REMOVED);
        }
        effective = removeStaleReferences(
                effective, removedFields, removedActions, stalePaths);
        restoreMandatoryVisibility(effective, facts, reasons, stalePaths);

        Meta meta = ensureMeta(effective);
        if (!stalePaths.isEmpty() || reasons.contains(REASON_PAGE_IDENTITY_CHANGED)) {
            meta.setOverlayStatus(STATUS_STALE);
        } else if (baselineChanged(storedMeta, page)) {
            meta.setOverlayStatus(STATUS_REBASED);
            reasons.add(REASON_BASE_RELEASE_CHANGED);
        } else {
            meta.setOverlayStatus(STATUS_CURRENT);
        }
        meta.setOverlayReasonCodes(List.copyOf(reasons));
        meta.setOverlayStalePaths(List.copyOf(stalePaths));
        return effective;
    }

    private PageSchemaDTO requireRuntimePage(String pageKey) {
        PageSchemaDTO page;
        try {
            page = pageSchemaService.findByPageKey(pageKey);
        } catch (RuntimeException failure) {
            throw new ValidationException(
                    ResponseCode.CommonValidationFailed,
                    "view.overlay.base-page-unavailable");
        }
        if (page == null) {
            throw new ValidationException(
                    ResponseCode.CommonValidationFailed,
                    "view.overlay.base-page-unavailable");
        }
        return page;
    }

    private ViewConfig markUnavailable(ViewConfig config) {
        Meta meta = ensureMeta(config);
        meta.setOverlayStatus(STATUS_STALE);
        meta.setOverlayReasonCodes(List.of(REASON_BASE_PAGE_UNAVAILABLE));
        meta.setOverlayStalePaths(List.of());
        return config;
    }

    /**
     * Structural columns present on every dynamic model table and exposed by the
     * runtime default view; a saved view may keep them in its column overlay even
     * though the page DSL does not declare them.
     */
    private static final java.util.Set<String> AUDIT_COLUMN_FIELDS =
            java.util.Set.of("created_at", "updated_at");

    private void rejectUnknownReferences(ViewConfig config, SchemaFacts facts) {
        if (config.getColumns() != null) {
            config.getColumns().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ColumnConfig::getFieldCode)
                    .filter(StringUtils::hasText)
                    // Structural audit columns exist on every dynamic model table (the
                    // runtime default view includes them) but are not declared in the
                    // page DSL, so the page-schema facts never contain them.
                    .filter(field -> !AUDIT_COLUMN_FIELDS.contains(field))
                    .filter(field -> !facts.fieldCodes().contains(field))
                    .findFirst()
                    .ifPresent(field -> { throw unknownReference("field", field); });
        }
        if (config.getSorts() != null) {
            config.getSorts().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ViewConfig.SortConfig::getFieldCode)
                    .filter(StringUtils::hasText)
                    .filter(field -> !facts.fieldCodes().contains(field))
                    .findFirst()
                    .ifPresent(field -> { throw unknownReference("field", field); });
        }
        if (config.getFilters() != null) {
            config.getFilters().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ViewConfig.FilterConfig::getFieldCode)
                    .filter(StringUtils::hasText)
                    .filter(field -> !facts.fieldCodes().contains(field))
                    .findFirst()
                    .ifPresent(field -> { throw unknownReference("field", field); });
        }
        if (config.getToolbarActions() != null) {
            config.getToolbarActions().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ToolbarActionConfig::getCode)
                    .filter(StringUtils::hasText)
                    .filter(action -> !facts.actionCodes().contains(action))
                    .findFirst()
                    .ifPresent(action -> { throw unknownReference("action", action); });
        }
    }

    private ValidationException unknownReference(String type, String identity) {
        return new ValidationException(
                ResponseCode.CommonValidationFailed,
                "view.overlay.unknown-" + type + ":" + identity);
    }

    private void rejectMandatoryHiding(ViewConfig config, SchemaFacts facts) {
        if (config.getColumns() != null) {
            for (ColumnConfig column : config.getColumns()) {
                if (column != null
                        && facts.mandatoryFieldCodes().contains(column.getFieldCode())
                        && Boolean.FALSE.equals(column.getVisible())) {
                    throw mandatoryViolation("field", column.getFieldCode());
                }
            }
        }
        if (config.getToolbarActions() != null) {
            for (ToolbarActionConfig action : config.getToolbarActions()) {
                if (action != null
                        && facts.mandatoryActionCodes().contains(action.getCode())
                        && Boolean.FALSE.equals(action.getVisible())) {
                    throw mandatoryViolation("action", action.getCode());
                }
            }
        }
    }

    private ValidationException mandatoryViolation(String type, String identity) {
        return new ValidationException(
                ResponseCode.CommonValidationFailed,
                "view.overlay.mandatory-cannot-hide:" + type + ":" + identity);
    }

    private void restoreMandatoryVisibility(
            ViewConfig config,
            SchemaFacts facts,
            Set<String> reasons,
            List<String> stalePaths) {
        if (config.getColumns() != null) {
            for (ColumnConfig column : config.getColumns()) {
                if (column != null
                        && facts.mandatoryFieldCodes().contains(column.getFieldCode())
                        && Boolean.FALSE.equals(column.getVisible())) {
                    column.setVisible(true);
                    reasons.add(REASON_MANDATORY_RESTORED);
                    addStalePath(stalePaths, "/columns/" + column.getFieldCode() + "/visible");
                }
            }
        }
        if (config.getToolbarActions() != null) {
            for (ToolbarActionConfig action : config.getToolbarActions()) {
                if (action != null
                        && facts.mandatoryActionCodes().contains(action.getCode())
                        && Boolean.FALSE.equals(action.getVisible())) {
                    action.setVisible(true);
                    reasons.add(REASON_MANDATORY_RESTORED);
                    addStalePath(stalePaths, "/toolbarActions/" + action.getCode() + "/visible");
                }
            }
        }
    }

    private ViewConfig removeStaleReferences(
            ViewConfig config,
            Set<String> removedFields,
            Set<String> removedActions,
            List<String> stalePaths) {
        if (removedFields.isEmpty() && removedActions.isEmpty()) {
            return config;
        }
        ObjectNode root = objectMapper.valueToTree(config);
        prune(root, "", removedFields, removedActions, stalePaths);
        return objectMapper.convertValue(root, ViewConfig.class);
    }

    private void prune(
            ObjectNode object,
            String path,
            Set<String> removedFields,
            Set<String> removedActions,
            List<String> stalePaths) {
        List<String> removeProperties = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            String childPath = path + "/" + name;
            if ("meta".equals(name)) {
                continue;
            }
            if (value.isTextual()
                    && name.endsWith("Field")
                    && removedFields.contains(value.textValue())) {
                removeProperties.add(name);
                addStalePath(stalePaths, childPath);
            } else if (value.isArray()) {
                pruneArray((ArrayNode) value, childPath, removedFields, removedActions, stalePaths);
            } else if (value.isObject()) {
                prune((ObjectNode) value, childPath, removedFields, removedActions, stalePaths);
            }
        }
        removeProperties.forEach(object::remove);
    }

    private void pruneArray(
            ArrayNode array,
            String path,
            Set<String> removedFields,
            Set<String> removedActions,
            List<String> stalePaths) {
        for (int index = array.size() - 1; index >= 0; index--) {
            JsonNode item = array.get(index);
            if (item.isNull() && isStructuredOverlayArray(path)) {
                array.remove(index);
                continue;
            }
            if (item.isTextual() && path.endsWith("DisplayFields")
                    && removedFields.contains(item.textValue())) {
                addStalePath(stalePaths, path + "/" + item.textValue());
                array.remove(index);
                continue;
            }
            if (!item.isObject()) {
                continue;
            }
            ObjectNode object = (ObjectNode) item;
            String fieldCode = text(object, "fieldCode", "field");
            String actionCode = text(object, "code");
            if (fieldCode != null && removedFields.contains(fieldCode)) {
                addStalePath(stalePaths, path + "/" + fieldCode);
                array.remove(index);
            } else if (actionCode != null
                    && path.endsWith("toolbarActions")
                    && removedActions.contains(actionCode)) {
                addStalePath(stalePaths, path + "/" + actionCode);
                array.remove(index);
            } else {
                prune(object, path + "/" + index,
                        removedFields, removedActions, stalePaths);
            }
        }
    }

    private boolean isStructuredOverlayArray(String path) {
        return path.endsWith("/columns")
                || path.endsWith("/sorts")
                || path.endsWith("/filters")
                || path.endsWith("/toolbarActions");
    }

    private void sanitizeNullOverlayItems(ViewConfig config) {
        if (config.getColumns() != null) {
            config.setColumns(config.getColumns().stream().filter(java.util.Objects::nonNull).toList());
        }
        if (config.getSorts() != null) {
            config.setSorts(config.getSorts().stream().filter(java.util.Objects::nonNull).toList());
        }
        if (config.getFilters() != null) {
            config.setFilters(config.getFilters().stream().filter(java.util.Objects::nonNull).toList());
        }
        if (config.getToolbarActions() != null) {
            config.setToolbarActions(config.getToolbarActions().stream()
                    .filter(java.util.Objects::nonNull).toList());
        }
    }

    private void stampCurrent(ViewConfig config, PageSchemaDTO page, SchemaFacts facts) {
        Meta meta = ensureMeta(config);
        PageSchemaRuntimeDTO runtime = page.getRuntime();
        meta.setOverlayStatus(STATUS_CURRENT);
        meta.setOverlayReasonCodes(List.of());
        meta.setOverlayStalePaths(List.of());
        meta.setBasePagePid(page.getPid());
        meta.setBaseReleasePid(runtime == null ? null : runtime.releasePid());
        meta.setBaseChannelVersion(runtime == null ? 0L : runtime.channelVersion());
        meta.setBaseSnapshotChecksum(runtimeChecksum(page));
        meta.setBaseFieldCodes(List.copyOf(facts.fieldCodes()));
        meta.setBaseActionCodes(List.copyOf(facts.actionCodes()));
    }

    private boolean baselineChanged(Meta storedMeta, PageSchemaDTO page) {
        PageSchemaRuntimeDTO runtime = page.getRuntime();
        String releasePid = runtime == null ? null : runtime.releasePid();
        long channelVersion = runtime == null ? 0L : runtime.channelVersion();
        return !java.util.Objects.equals(storedMeta.getBaseReleasePid(), releasePid)
                || !java.util.Objects.equals(
                    storedMeta.getBaseChannelVersion(), channelVersion)
                || !java.util.Objects.equals(
                    storedMeta.getBaseSnapshotChecksum(), runtimeChecksum(page));
    }

    private String runtimeChecksum(PageSchemaDTO page) {
        PageSchemaRuntimeDTO runtime = page.getRuntime();
        if (runtime != null && StringUtils.hasText(runtime.snapshotChecksum())) {
            return runtime.snapshotChecksum();
        }
        return "page-schema:" + page.getPid() + ":"
                + (page.getRowVersion() == null ? page.getVersion() : page.getRowVersion());
    }

    private SchemaFacts schemaFacts(PageSchemaDTO page) {
        Set<String> fields = new TreeSet<>();
        Set<String> actions = new TreeSet<>();
        Set<String> mandatoryFields = new TreeSet<>();
        Set<String> mandatoryActions = new TreeSet<>();
        collect(objectMapper.valueToTree(page.getBlocks()), null,
                fields, actions, mandatoryFields, mandatoryActions);
        // The runtime table renders every model-declared column plus the
        // structural audit columns, so a saved view's column overlay may
        // reference them even though the page DSL block does not declare
        // them. Without this, saving a personal view whose column state
        // carries a model column fails overlay validation.
        if (StringUtils.hasText(page.getModelCode())) {
            metaModelService.getModelDefinition(page.getModelCode()).ifPresent(definition -> {
                if (definition.getFields() != null) {
                    definition.getFields().stream()
                            .map(com.auraboot.framework.meta.dto.FieldDefinition::getCode)
                            .filter(StringUtils::hasText)
                            .forEach(fields::add);
                }
                // structural audit columns live on the model's physical table
                fields.add("created_at");
                fields.add("updated_at");
            });
        }
        return new SchemaFacts(fields, actions, mandatoryFields, mandatoryActions);
    }

    private void collect(
            JsonNode node,
            String parentKey,
            Set<String> fields,
            Set<String> actions,
            Set<String> mandatoryFields,
            Set<String> mandatoryActions) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collect(child, parentKey,
                    fields, actions, mandatoryFields, mandatoryActions));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        boolean fieldContainer = "columns".equals(parentKey) || "fields".equals(parentKey);
        boolean actionContainer = "actions".equals(parentKey)
                || "buttons".equals(parentKey)
                || "toolbarActions".equals(parentKey);
        String field = fieldContainer ? text(node, "fieldCode", "field") : null;
        String action = actionContainer ? text(node, "code", "id") : null;
        if (field != null) {
            fields.add(field);
        }
        if (action != null) {
            actions.add(action);
        }
        if (node.path("mandatory").asBoolean(false)) {
            if (field != null) {
                mandatoryFields.add(field);
            }
            if (action != null) {
                mandatoryActions.add(action);
            }
        }
        node.properties().forEach(entry -> collect(
                entry.getValue(), entry.getKey(), fields, actions,
                mandatoryFields, mandatoryActions));
    }

    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && StringUtils.hasText(value.textValue())) {
                return value.textValue();
            }
        }
        return null;
    }

    private Set<String> removed(List<String> before, Set<String> current) {
        if (before == null || before.isEmpty()) {
            return Set.of();
        }
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(current);
        return removed;
    }

    private Meta ensureMeta(ViewConfig config) {
        if (config.getMeta() == null) {
            config.setMeta(new Meta());
        }
        return config.getMeta();
    }

    private ViewConfig copy(ViewConfig config) {
        if (config == null) {
            return new ViewConfig();
        }
        return objectMapper.convertValue(config, ViewConfig.class);
    }

    private void addStalePath(List<String> paths, String path) {
        if (paths.size() < MAX_STALE_PATHS && !paths.contains(path)) {
            paths.add(path);
        }
    }

    private record SchemaFacts(
            Set<String> fieldCodes,
            Set<String> actionCodes,
            Set<String> mandatoryFieldCodes,
            Set<String> mandatoryActionCodes) {
    }
}
