package com.auraboot.framework.application.release;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.DataTypeMapping;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.ApplicationNavigationPolicy;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import com.fasterxml.jackson.core.JsonProcessingException;

/** Materializes runtime metadata directly from a tenant's immutable active Application Release. */
@Service
public final class ApplicationRuntimeDefinitionCatalog {
    private final ApplicationDefinitionMapper definitions;
    private final ApplicationDefinitionResolver resolver;
    private final ObjectMapper mapper;

    public ApplicationRuntimeDefinitionCatalog(ApplicationDefinitionMapper definitions,
                                               ApplicationDefinitionResolver resolver,
                                               ObjectMapper mapper) {
        this.definitions = definitions;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    public Optional<List<Dashboard>> dashboards(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return Optional.empty();
        Set<String> codes = new LinkedHashSet<>();
        var result = release.components().stream()
                .flatMap(component -> list(component.manifest().getDashboards()).stream()).map(source -> {
                    if (source.getCode() == null || !codes.add(source.getCode())) {
                        throw unavailable("Dashboard key must be present and unique in the active Application Release");
                    }
                    if (!"global".equals(source.getEffectiveScope())) {
                        throw unavailable("Release dashboards must declare global scope: " + source.getCode());
                    }
                    Dashboard dashboard = new Dashboard();
                    dashboard.setTenantId(tenantId);
                    dashboard.setCode(source.getCode());
                    dashboard.setTitle(source.getTitle());
                    dashboard.setDescription(source.getDescription());
                    dashboard.setScope(source.getEffectiveScope());
                    dashboard.setStatus(source.getEffectiveStatus());
                    dashboard.setIsDefault(source.getIsDefault());
                    dashboard.setSortOrder(source.getSortOrder());
                    dashboard.setLayoutConfig(mapper.valueToTree(source.getLayoutConfig() == null
                            ? Map.of("columns", 12, "rowHeight", 100, "gap", 16, "compactType", "vertical")
                            : source.getLayoutConfig()));
                    dashboard.setWidgets(mapper.valueToTree(list(source.getWidgets())));
                    Map<String, Object> extension = new LinkedHashMap<>(source.getExtension() == null
                            ? Map.of() : source.getExtension());
                    extension.put("releaseId", release.release().releaseId());
                    dashboard.setExtension(mapper.valueToTree(extension));
                    return dashboard;
                }).sorted(Comparator.comparing(dashboard -> dashboard.getSortOrder() == null
                        ? 0 : dashboard.getSortOrder())).toList();
        return Optional.of(result);
    }

    /** Query and field whitelist are projected together from one immutable binding selection. */
    public record BoundNamedQuery(NamedQuery query, List<NamedQueryField> fields) { }

    public Optional<BoundNamedQuery> findNamedQuery(long tenantId, String applicationCode, String code) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return Optional.empty();
        var matches = release.components().stream()
                .flatMap(component -> list(component.manifest().getNamedQueries()).stream())
                .filter(query -> code.equals(query.getCode())).toList();
        if (matches.size() > 1) throw unavailable("Named query key is ambiguous in the active Application Release: " + code);
        if (matches.isEmpty()) return Optional.empty();
        var source = matches.getFirst();
        NamedQuery query = new NamedQuery(tenantId, code, source.getEffectiveTitle(), source.getFromSql());
        query.setDescription(source.getDescription());
        query.setResourceCode(source.getResourceCode());
        query.setActionCode(source.getActionCode());
        query.setBaseWhere(source.getBaseWhere());
        query.setDefaultOrder(source.getDefaultOrder());
        query.setPolicy(source.getPolicy());
        query.setStatus(source.getStatus() == null ? "draft" : source.getStatus().toLowerCase(java.util.Locale.ROOT));
        Set<String> fieldCodes = new LinkedHashSet<>();
        var fields = list(source.getFields()).stream().map(field -> {
            if (field.getFieldCode() == null || !fieldCodes.add(field.getFieldCode())
                    || field.getColumnExpr() == null || field.getColumnExpr().isBlank()
                    || field.getDataType() == null || field.getDataType().isBlank()) {
                throw unavailable("Release named query requires a complete, unique field whitelist: " + code);
            }
            NamedQueryField result = new NamedQueryField(tenantId, code, field.getFieldCode(),
                    field.getColumnExpr(), field.getDataType());
            if (field.getOperators() != null) result.setOperatorList(field.getOperators());
            result.setDictCode(field.getDictCode());
            result.setSortable(Boolean.TRUE.equals(field.getSortable()));
            result.setSearchable(!Boolean.FALSE.equals(field.getSearchable()));
            result.setUiComponent(field.getUiComponent() == null ? "text" : field.getUiComponent());
            result.setPlaceholder(field.getPlaceholder());
            result.setDefaultValue(field.getDefaultValue());
            result.setLinkedField(field.getLinkedField());
            result.setRequired(Boolean.TRUE.equals(field.getRequired()));
            result.setDisplayName(field.getDisplayName());
            result.setSortOrder(field.getSortOrder() == null ? 0 : field.getSortOrder());
            result.setFieldGroup(field.getFieldGroup());
            result.setUiConfig(field.getUiConfig());
            result.setSource("release");
            return result;
        }).sorted(Comparator.comparing(NamedQueryField::getSortOrder)).toList();
        if (fields.isEmpty()) throw unavailable("Release named query requires an explicit field whitelist: " + code);
        return Optional.of(new BoundNamedQuery(query, fields));
    }

    public Optional<DictDTO> findDict(long tenantId, String applicationCode, String code) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return Optional.empty();
        var matches = release.components().stream().flatMap(component -> list(component.manifest().getDicts()).stream())
                .filter(dict -> code.equals(dict.getCode())).toList();
        if (matches.size() > 1) throw unavailable("Dictionary key is ambiguous in the active Application Release: " + code);
        if (matches.isEmpty()) return Optional.empty();
        var source = matches.getFirst();
        Set<String> values = new LinkedHashSet<>();
        List<DataSourceItemBean> items = list(source.getItems()).stream().map(item -> {
            if (item.getValue() == null || !values.add(item.getValue())) {
                throw unavailable("Release dictionary values must be present and unique: " + code);
            }
            DataSourceItemBean result = new DataSourceItemBean();
            result.setValue(item.getValue());
            result.setLabel(item.getEffectiveLabel());
            result.setOrder(item.getSortNo());
            result.setDisabled(!"enabled".equals(item.getStatus()));
            Map<String, Object> extra = new LinkedHashMap<>(item.getExtra() == null ? Map.of() : item.getExtra());
            extra.put("labels", item.getAllLocalizedLabels());
            if (item.getParentValue() != null) extra.put("parentValue", item.getParentValue());
            result.setExtra(extra);
            return result;
        }).sorted(Comparator.comparing(item -> item.getOrder() == null ? 0 : item.getOrder())).toList();
        Map<String, Object> extension = new LinkedHashMap<>(source.getExtension() == null ? Map.of() : source.getExtension());
        extension.put("releaseId", release.release().releaseId());
        return Optional.of(DictDTO.builder().code(code).name(source.getEffectiveName()).dictType(source.getDictType())
                .description(source.getDescription()).items(items).enabled(true).status("published")
                .extendedProps(mapper.valueToTree(extension)).build());
    }

    /** Rendering metadata uses the same bound definitions as dynamic reads. */
    public Optional<List<MetaFieldDTO>> findFieldMetadata(long tenantId, String applicationCode, String modelCode) {
        return findModel(tenantId, applicationCode, modelCode).map(model -> model.getFields().stream()
                .map(field -> {
                    Map<String, Object> extension = new LinkedHashMap<>(
                            field.getExtraProps() == null ? Map.of() : field.getExtraProps());
                    if (field.getDisplayName() != null) extension.put("displayName", field.getDisplayName());
                    if (field.getDefaultValue() != null) extension.put("defaultValue", field.getDefaultValue());
                    return MetaFieldDTO.builder().code(field.getCode()).dataType(field.getDataType())
                            .extension(extension).fieldOrder(field.getSortOrder()).required(field.getRequired())
                            .feature(Map.of("required", Boolean.TRUE.equals(field.getRequired()),
                                    "unique", Boolean.TRUE.equals(field.getUnique())))
                            .dictCode(extension.get("dictCode") instanceof String code ? code : null)
                            .refTarget(field.getRefTarget() == null ? null : JsonUtil.toMap(field.getRefTarget()))
                            .uiSchema(extension.get("uiSchema") == null ? null : JsonUtil.toMap(extension.get("uiSchema")))
                            .build();
                }).toList());
    }

    /** Immutable physical-source identities from one exact active Release. */
    public record BoundModelSource(String code, String tableName) { }

    public Optional<List<BoundModelSource>> modelSources(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return Optional.empty();
        List<BoundModelSource> sources = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (var component : release.components()) {
            for (ModelDefinitionDTO model : list(component.manifest().getModels())) {
                if (model.getCode() == null || model.getCode().isBlank() || !codes.add(model.getCode())) {
                    throw unavailable("Model key is missing or ambiguous in the active Application Release");
                }
                String table = model.getTableName();
                if (table == null || table.isBlank()) table = SystemFieldConstants.generateTableName(model.getCode());
                sources.add(new BoundModelSource(model.getCode(), table));
            }
        }
        return Optional.of(List.copyOf(sources));
    }

    /** Empty means that this tenant has no active binding and must retain its existing read path. */
    public Optional<ModelDefinition> findModel(long tenantId, String applicationCode, String modelCode) {
        ApplicationDefinitionResolver.ReleaseDefinitions release = activeRelease(tenantId, applicationCode);
        if (release == null) return Optional.empty();

        List<ModelDefinitionDTO> matches = new ArrayList<>();
        for (var component : release.components()) {
            for (ModelDefinitionDTO model : list(component.manifest().getModels())) {
                if (modelCode.equals(model.getCode())) matches.add(model);
            }
        }
        if (matches.size() > 1) throw unavailable("Model key is ambiguous in the active Application Release: " + modelCode);
        if (matches.isEmpty()) return Optional.empty();

        ModelDefinitionDTO source = matches.getFirst();
        List<FieldDefinition> fields = releaseFields(release, modelCode);
        Map<String, Object> extension = source.getExtension() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getExtension());
        return Optional.of(ModelDefinition.builder()
                .code(source.getCode())
                .name(source.getCode())
                .displayName(source.getEffectiveDisplayName())
                .description(source.getDescription())
                .tableName(source.getTableName() == null || source.getTableName().isBlank()
                        ? SystemFieldConstants.generateTableName(source.getCode()) : source.getTableName())
                .modelType(source.getModelType())
                .modelCategory(source.getModelCategory())
                .sourceType("physical")
                .primaryKey("pid")
                .version(1)
                .status("published")
                .fields(fields)
                .relations(List.of())
                .softDelete(false)
                .immutable(Boolean.TRUE.equals(source.getImmutable()))
                .commandOnlyCreate(Boolean.TRUE.equals(source.getCommandOnlyCreate()))
                .extension(Map.copyOf(extension))
                .build());
    }

    public Optional<CommandDefinitionDTO> findCommand(long tenantId, String applicationCode, String commandCode) {
        return allCommands(tenantId, applicationCode).stream()
                .filter(command -> commandCode.equals(command.getCode())).findFirst();
    }

    public List<CommandDefinitionDTO> commandsForModel(long tenantId, String applicationCode, String modelCode) {
        return allCommands(tenantId, applicationCode).stream()
                .filter(command -> modelCode.equals(command.getModelCode())).toList();
    }

    public List<CommandDefinitionDTO> allCommands(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return List.of();
        Map<String, CommandDefinitionDTO> result = new LinkedHashMap<>();
        for (var component : release.components()) {
            Map<String, List<com.auraboot.framework.meta.dto.BindingRuleDTO>> rulesByCommand = new LinkedHashMap<>();
            for (com.auraboot.framework.plugin.dto.imports.BindingRuleDTO source
                    : list(component.manifest().getBindingRules())) {
                if (source.getCommandCode() == null || source.getCommandCode().isBlank()) continue;
                var rule = new com.auraboot.framework.meta.dto.BindingRuleDTO();
                rule.setRuleType(source.getRuleType());
                rule.setExpression(source.getExpression());
                rule.setTargetModel(source.getTargetModel());
                rule.setTargetField(source.getTargetField());
                rule.setSourceField(source.getSourceField());
                rule.setHandlerClass(source.getHandlerClass());
                rule.setEventType(source.getEventType());
                rule.setConfig(json(source.getConfig(), null));
                rule.setSequence(source.getSequence());
                rule.setEnabled(source.getEnabled());
                rulesByCommand.computeIfAbsent(source.getCommandCode(), ignored -> new ArrayList<>()).add(rule);
            }
            for (com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO source
                    : list(component.manifest().getCommands())) {
                CommandDefinitionDTO command = new CommandDefinitionDTO();
                command.setCode(source.getCode());
                command.setDisplayName(source.getEffectiveDisplayName());
                command.setDescription(source.getDescription());
                command.setModelCode(source.getModelCode());
                command.setType(source.getType());
                command.setInputSchema(json(source.getInputSchema(), "{}"));
                command.setTargetModels(json(source.getTargetModels(), "[]"));
                command.setExecutionConfig(json(source.getConsolidatedExecutionConfig(), "{}"));
                command.setCmdRiskLevel(source.getCmdRiskLevel() == null ? "L1" : source.getCmdRiskLevel());
                command.setVersion(1);
                command.setSemver("1.0.0");
                command.setIsCurrent(true);
                command.setStatus("published");
                command.setBindingRules(List.copyOf(rulesByCommand.getOrDefault(command.getCode(), List.of())));
                if (result.putIfAbsent(command.getCode(), command) != null) {
                    throw unavailable("Command key is ambiguous in the active Application Release: " + command.getCode());
                }
            }
        }
        return List.copyOf(result.values());
    }

    public List<RoleDefinitionDTO> roles(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return List.of();
        Map<String, RoleDefinitionDTO> result = new LinkedHashMap<>();
        for (var component : release.components()) {
            for (RoleDefinitionDTO role : list(component.manifest().getRoles())) {
                if (result.putIfAbsent(role.getCode(), role) != null) {
                    throw unavailable("Role key is ambiguous in the active Application Release: " + role.getCode());
                }
            }
        }
        return List.copyOf(result.values());
    }

    public Set<String> permissionsForRoles(long tenantId, String applicationCode, Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return Set.of();
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        for (RoleDefinitionDTO role : roles(tenantId, applicationCode)) {
            if (roleCodes.contains(role.getCode()) && role.getPermissions() != null) {
                role.getPermissions().stream().filter(code -> code != null && !code.isBlank()).forEach(permissions::add);
            }
        }
        return Set.copyOf(permissions);
    }

    /**
     * Mirrors config-import semantics: tenant_admin owns every explicit, referenced, and generated
     * model permission available from the release.
     */
    public Set<String> permissionCodes(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return Set.of();
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        for (var component : release.components()) {
            for (com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO permission
                    : list(component.manifest().getPermissions())) {
                if (permission.getCode() != null && !permission.getCode().isBlank()) {
                    permissions.add(permission.getCode());
                }
            }
            for (RoleDefinitionDTO role : list(component.manifest().getRoles())) {
                if (role.getPermissions() != null) {
                    role.getPermissions().stream().filter(code -> code != null && !code.isBlank())
                            .forEach(permissions::add);
                }
            }
            for (com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO command
                    : list(component.manifest().getCommands())) {
                if (command.getPermissions() != null) {
                    command.getPermissions().stream().filter(code -> code != null && !code.isBlank())
                            .forEach(permissions::add);
                }
            }
            for (ModelDefinitionDTO model : list(component.manifest().getModels())) {
                if (model.getCode() == null || model.getCode().isBlank()) continue;
                for (String action : List.of("read", "create", "update", "delete", "export", "import")) {
                    permissions.add("model." + model.getCode() + "." + action);
                }
            }
        }
        return Set.copyOf(permissions);
    }

    /** Release menus carry no tenant-local identity; their hierarchy is built from stable menu codes. */
    public List<Menu> menuTree(long tenantId, String applicationCode) {
        var release = activeRelease(tenantId, applicationCode);
        if (release == null) return List.of();
        Map<String, Menu> menus = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        for (var component : release.components()) {
            for (MenuDefinitionDTO source : list(component.manifest().getMenus())) {
                Menu menu = new Menu();
                menu.setCode(source.getCode());
                menu.setName(source.getEffectiveName());
                menu.setPath(source.getPath());
                menu.setComponent(source.getComponent());
                menu.setIcon(source.getIcon());
                menu.setType(source.getType());
                menu.setPermissionCode(source.getPermissionCode());
                menu.setVisible(!Boolean.FALSE.equals(source.getVisible()));
                menu.setOrderNo(source.getOrderNo() == null ? 0 : source.getOrderNo());
                menu.setI18nKey(source.getI18nKey());
                menu.setRedirect(source.getRedirect());
                menu.setPageKey(source.getPageKey());
                if (source.getExtension() != null) {
                    if (source.getExtension().containsKey("applicationNavigation")
                            && source.getParentCode() != null && !source.getParentCode().isBlank()) {
                        throw unavailable("Application navigation policy must be declared on a Release root menu");
                    }
                    menu.setExtension(mapper.convertValue(source.getExtension(), ExtensionBean.class));
                }
                if (menus.putIfAbsent(source.getCode(), menu) != null) {
                    throw unavailable("Menu key is ambiguous in the active Application Release: " + source.getCode());
                }
                parents.put(source.getCode(), source.getParentCode());
            }
        }
        List<Menu> roots = new ArrayList<>();
        for (var entry : menus.entrySet()) {
            String parentCode = parents.get(entry.getKey());
            if (parentCode == null || parentCode.isBlank()) {
                roots.add(entry.getValue());
                continue;
            }
            Menu parent = menus.get(parentCode);
            if (parent == null) throw unavailable("Menu references a missing Release parent: " + parentCode);
            if (parent.getChildren() == null) parent.setChildren(new ArrayList<>());
            parent.getChildren().add(entry.getValue());
        }
        roots = ApplicationNavigationPolicy.groupRoots(roots);
        sortMenus(roots);
        return List.copyOf(roots);
    }

    private static void sortMenus(List<Menu> menus) {
        menus.sort(Comparator.comparing(menu -> menu.getOrderNo() == null ? 0 : menu.getOrderNo()));
        for (Menu menu : menus) if (menu.getChildren() != null) sortMenus(menu.getChildren());
    }

    private List<FieldDefinition> releaseFields(ApplicationDefinitionResolver.ReleaseDefinitions release,
                                                String modelCode) {
        Map<String, FieldDefinitionDTO> fieldsByCode = new LinkedHashMap<>();
        List<ModelFieldBindingDTO> bindings = new ArrayList<>();
        for (var component : release.components()) {
            for (FieldDefinitionDTO field : list(component.manifest().getFields())) {
                if (fieldsByCode.putIfAbsent(field.getCode(), field) != null) {
                    throw unavailable("Field key is ambiguous in the active Application Release: " + field.getCode());
                }
            }
            for (ModelFieldBindingDTO binding : list(component.manifest().getModelFieldBindings())) {
                if (modelCode.equals(binding.getModelCode())) bindings.add(binding);
            }
        }
        bindings.sort(Comparator.comparing(binding -> binding.getSequence() == null ? 0 : binding.getSequence()));
        List<FieldDefinition> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (ModelFieldBindingDTO binding : bindings) {
            FieldDefinitionDTO field = fieldsByCode.get(binding.getFieldCode());
            if (field == null) throw unavailable("Model binding references a missing Release field: " + binding.getFieldCode());
            if (!used.add(field.getCode())) throw unavailable("Model contains a duplicated Release field: " + field.getCode());
            result.add(toField(field, binding));
        }
        addSystemFields(result, used);
        result.sort(Comparator.comparing(field -> field.getSortOrder() == null ? 0 : field.getSortOrder()));
        return List.copyOf(result);
    }

    private FieldDefinition toField(FieldDefinitionDTO source, ModelFieldBindingDTO binding) {
        var constraints = source.getConstraints();
        Map<String, Object> extra = source.getExtension() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getExtension());
        if (source.getUiSchema() != null) extra.put("uiSchema", source.getUiSchema());
        if (source.getDictCode() != null) extra.put("dictCode", source.getDictCode());
        FieldDefinition.ImmutableWhen immutableWhen = source.getImmutableWhen() == null ? null
                : FieldDefinition.ImmutableWhen.builder()
                    .field(source.getImmutableWhen().getField()).in(source.getImmutableWhen().getIn()).build();
        boolean searchable = Boolean.TRUE.equals(value(source.getFeature(), "searchable"))
                || Boolean.TRUE.equals(value(binding.getDisplayConfig(), "searchable"));
        boolean sortable = Boolean.TRUE.equals(value(source.getFeature(), "sortable"))
                || Boolean.TRUE.equals(value(binding.getDisplayConfig(), "sortable"));
        return FieldDefinition.builder()
                .code(source.getCode()).name(source.getCode()).displayName(source.getEffectiveDisplayName())
                .description(source.getDescription()).dataType(normalizeType(source.getDataType()))
                .columnName(source.getColumnName() == null || source.getColumnName().isBlank()
                        ? source.getCode() : source.getColumnName())
                .required(Boolean.TRUE.equals(binding.getRequired()))
                .searchable(searchable).sortable(sortable)
                .unique(constraints != null && Boolean.TRUE.equals(constraints.getUnique()))
                .defaultValue(source.getDefaultValue())
                .maxLength(constraints == null ? null : constraints.getMaxLength())
                .minLength(constraints == null ? null : constraints.getMinLength())
                .precision(constraints == null ? null : constraints.getPrecision())
                .scale(constraints == null ? null : constraints.getScale())
                .sortOrder(binding.getSequence()).dataTypeMapping(typeMapping(source.getDataType()))
                .validationRules(List.of()).immutable(Boolean.TRUE.equals(source.getImmutable()))
                .immutableWhen(immutableWhen).allowedWriterCommands(source.getAllowedWriterCommands())
                .refTarget(refTarget(source, extra))
                .extraProps(Map.copyOf(extra)).build();
    }

    private FieldDefinition.RefTarget refTarget(FieldDefinitionDTO source, Map<String, Object> extension) {
        Map<String, Object> declared = source.getRefTarget();
        if (declared != null && !declared.isEmpty()) {
            return mapper.convertValue(declared, FieldDefinition.RefTarget.class);
        }
        Object target = extension.get("refModelCode");
        if (target == null && source.getReferenceModelCode() != null) target = source.getReferenceModelCode();
        if (target == null) return null;
        return FieldDefinition.RefTarget.builder()
                .targetEntity(String.valueOf(target))
                .valueField("pid")
                .displayField(extension.get("refDisplayField") == null
                        ? "name" : String.valueOf(extension.get("refDisplayField")))
                .build();
    }

    private ApplicationDefinitionResolver.ReleaseDefinitions activeRelease(long tenantId, String applicationCode) {
        if (applicationCode == null || applicationCode.isBlank()) return null;
        ApplicationDefinitionMapper.ReleaseRow row = definitions.findBoundRelease(tenantId, applicationCode);
        if (row == null || "shadow".equals(row.status)) return null;
        if (!"active".equals(row.status)) throw unavailable("Unsupported tenant application binding status");
        var release = resolver.boundRelease(tenantId, applicationCode);
        if (!"active".equals(release.release().bindingStatus())) return null;
        return release;
    }

    private static void addSystemFields(List<FieldDefinition> target, Set<String> used) {
        addSystem(target, used, "id", "long", -1000, false);
        addSystem(target, used, "pid", "string", -999, true);
        addSystem(target, used, "created_at", "datetime", -998, false);
        addSystem(target, used, "updated_at", "datetime", -997, false);
        addSystem(target, used, "created_by", "long", -996, false);
        addSystem(target, used, "updated_by", "long", -995, false);
        addSystem(target, used, "tenant_id", "long", -994, false);
        addSystem(target, used, "row_version", "int", -993, false);
    }

    private static void addSystem(List<FieldDefinition> target, Set<String> used, String code,
                                  String type, int order, boolean primaryKey) {
        if (!used.add(code)) return;
        target.add(FieldDefinition.builder().code(code).name(code).columnName(code).dataType(type)
                .primaryKey(primaryKey).required(false).sortOrder(order).dataTypeMapping(typeMapping(type)).build());
    }

    private static Object value(Map<String, Object> values, String key) {
        return values == null ? null : values.get(key);
    }

    private static String normalizeType(String type) {
        return type == null ? null : type.toLowerCase(java.util.Locale.ROOT);
    }

    private static DataTypeMapping typeMapping(String type) {
        String normalized = normalizeType(type);
        return switch (normalized == null ? "" : normalized) {
            case "int", "integer" -> DataTypeMapping.builder().javaType("Integer").jdbcType("INTEGER").dbType("INTEGER").build();
            case "long" -> DataTypeMapping.builder().javaType("Long").jdbcType("BIGINT").dbType("BIGINT").build();
            case "decimal" -> DataTypeMapping.builder().javaType("BigDecimal").jdbcType("DECIMAL").dbType("DECIMAL").build();
            case "boolean" -> DataTypeMapping.builder().javaType("Boolean").jdbcType("BOOLEAN").dbType("BOOLEAN").build();
            case "date" -> DataTypeMapping.builder().javaType("LocalDate").jdbcType("DATE").dbType("DATE").build();
            case "datetime" -> DataTypeMapping.builder().javaType("Instant").jdbcType("TIMESTAMP").dbType("TIMESTAMP").build();
            case "json" -> DataTypeMapping.builder().javaType("Object").jdbcType("OTHER").dbType("JSONB").build();
            default -> DataTypeMapping.builder().javaType("String").jdbcType("VARCHAR").dbType("VARCHAR").build();
        };
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String json(Object value, String fallback) {
        if (value == null) return fallback;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new DataRetrievalFailureException("Application Release command cannot be materialized", failure);
        }
    }

    private static DataRetrievalFailureException unavailable(String message) {
        return new DataRetrievalFailureException(message);
    }
}
