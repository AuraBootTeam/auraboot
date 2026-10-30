package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Delegates resource import responsibilities while retaining the public importer contract. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginResourceImporterImpl implements PluginResourceImporter {
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final SchemaManagementService schemaManagementService;
    private final DictService dictService;
    private final CommandService commandService;
    private final PermissionService permissionService;
    private final DataScopeService dataScopeService;
    private final RolePermissionService rolePermissionService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final MenuService menuService;
    private final PageSchemaService pageSchemaService;
    private final NamedQueryService namedQueryService;
    private final DashboardService dashboardService;
    private final com.auraboot.framework.environment.service.EnvironmentService environmentService;
    private final JdbcTemplate jdbcTemplate;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DecisionUsageIndexService> usageIndexServiceProvider;
    private final MetaModelMapper metaModelMapper;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final DictMapper dictMapper;
    private final NamedQueryMapper namedQueryMapper;
    @org.springframework.lang.Nullable
    private final com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle agentDefinitionResources;
    private final PluginRecordMapper pluginRecordMapper;
    private final Map<String, Long> menuCodeToIdMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final MetaFieldMapper metaFieldMapper;
    private final ExtensionConverter extensionConverter;
    private final PluginResourceMapper pluginResourceMapper;
    private final PluginPublicPublicationLifecycle publicPublicationLifecycle;
    private final com.auraboot.framework.meta.service.impl.CommandMetadataCacheService commandMetadataCache;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CacheManager cacheManager;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    void verifyPublishedModelGuards(String modelCode) {
        if (applicationEventPublisher == null) {
            throw new com.auraboot.framework.plugin.exception.PluginException("Model publish guard dispatcher is unavailable");
        }
        applicationEventPublisher.publishEvent(
                new com.auraboot.framework.meta.event.ModelTablePublishedEvent(this, modelCode));
    }

    @Override
    public void beginFieldImportBatch() {
        fieldsModelsBindings().beginFieldImportBatch();
    }

    @Override
    public void endFieldImportBatch(boolean completed) {
        fieldsModelsBindings().endFieldImportBatch(completed);
    }

    @Override
    public boolean checkModelExists(Long tenantId, String code) {
        return fieldsModelsBindings().checkModelExists(tenantId, code);
    }

    @Override
    public boolean checkFieldExists(Long tenantId, String code) {
        return fieldsModelsBindings().checkFieldExists(tenantId, code);
    }

    @Override
    public boolean checkCommandExists(Long tenantId, String code) {
        return commandsBindingRules().checkCommandExists(tenantId, code);
    }

    @Override
    public boolean checkPermissionExists(Long tenantId, String code) {
        return accessPolicies().checkPermissionExists(tenantId, code);
    }

    @Override
    public boolean checkRoleExists(Long tenantId, String code) {
        return accessPolicies().checkRoleExists(tenantId, code);
    }

    @Override
    public boolean checkMenuExists(Long tenantId, String code) {
        return pageNavigationResources().checkMenuExists(tenantId, code);
    }

    /**
     * Clear the menu code-to-ID map. Should be called before each plugin import session.
     */
    public void clearMenuCodeMap() {
        pageNavigationResources().clearMenuCodeMap();
    }

    @Override
    public boolean checkDashboardExists(Long tenantId, String code) {
        return dashboardService.findByCode(code) != null;
    }

    @Override
    public boolean checkPageExists(Long tenantId, String pageKey) {
        return pageNavigationResources().checkPageExists(tenantId, pageKey);
    }

    @Override
    public boolean checkDictExists(Long tenantId, String code) {
        return pageNavigationResources().checkDictExists(tenantId, code);
    }

    @Override
    public boolean checkNamedQueryExists(Long tenantId, String code) {
        return namedQueries().checkNamedQueryExists(tenantId, code);
    }

    @Override
    public boolean checkAgentDefinitionExists(Long tenantId, String agentCode) {
        return resourceLifecycle().checkAgentDefinitionExists(tenantId, agentCode);
    }

    @Override
    public PluginResource importModel(ModelDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        return fieldsModelsBindings().importModel(dto, pluginPid, importId, tenantId, conflictStrategy, autoPublish);
    }

    @Override
    public PluginResource importField(FieldDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        return fieldsModelsBindings().importField(dto, pluginPid, importId, tenantId, conflictStrategy, autoPublish);
    }

    @Override
    public PluginResource importModelFieldBinding(ModelFieldBindingDTO dto, String pluginPid, String importId,
                                                   Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return fieldsModelsBindings().importModelFieldBinding(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importCommand(CommandDefinitionDTO dto, String pluginPid, String importId,
                                         Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                         Boolean autoPublish) {
        return commandsBindingRules().importCommand(dto, pluginPid, importId, tenantId, conflictStrategy, autoPublish);
    }

    @Override
    public PluginResource importBindingRule(BindingRuleDTO dto, String pluginPid, String importId,
                                             Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return commandsBindingRules().importBindingRule(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importPermission(PermissionDefinitionDTO dto, String pluginPid, String importId,
                                            Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return accessPolicies().importPermission(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importRole(RoleDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return accessPolicies().importRole(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    /**
     * Reconcile declared role permissions for an installed plugin directory: validate
     * the directory and installed version, then strictly check every declared role and
     * permission resolves and is bound for the current tenant. The caller owns the
     * transaction boundary.
     */
    @Override
    public int reconcileDirectoryRolePermissions(String directoryPath,
                                                 PluginDirectoryLoader directoryLoader,
                                                 PluginRecordMapper pluginRecordMapper) {
        return accessPolicies().reconcileDirectoryRolePermissions(directoryPath, directoryLoader, pluginRecordMapper);
    }

    public boolean reconcileRolePermissions(RoleDefinitionDTO dto, Long tenantId) {
        return accessPolicies().reconcileRolePermissions(dto, tenantId);
    }

    @Override
    public PluginResource importMenu(MenuDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return pageNavigationResources().importMenu(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importPage(PageSchemaDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                      Boolean autoPublish) {
        return pageNavigationResources().importPage(dto, pluginPid, importId, tenantId, conflictStrategy, autoPublish);
    }

    /**
     * Import a dashboard from the first-class {@code config/dashboards/*.json} contract (Plan #8).
     *
     * <p>Conflict strategy is honoured at the dashboard level (by code):
     * <ul>
     *   <li>ERROR       → throw if a dashboard with the same code already exists</li>
     *   <li>SKIP        → return a SKIP record when dashboard already exists</li>
     *   <li>OVERWRITE / OVERWRITE_SAFE → update widgets + layout in place</li>
     * </ul>
     */
    @Override
    public PluginResource importDashboard(DashboardDefinitionDTO dto, String pluginPid, String importId,
                                          Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return pageNavigationResources().importDashboard(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importDict(DictDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return pageNavigationResources().importDict(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importNamedQuery(NamedQueryDefinitionDTO dto, String pluginPid, String importId,
                                           Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return namedQueries().importNamedQuery(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    @Override
    public PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                                Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        return resourceLifecycle().importAgentDefinition(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    /**
     * Rollback handlers below run during plugin un-install or import-failure compensation.
     *
     * The {@code catch (Exception)} branches here are intentional:
     *   1. They are best-effort cleanups; a single failed delete must not block
     *      subsequent rollback steps for other resource types.
     *   2. They fall back to a direct mapper/jdbc archive call when the service
     *      method fails — the resource still ends up in a tombstoned state.
     *   3. {@code log.warn} carries the message + cause via SLF4J's {@code {}}
     *      placeholder, so trace context is preserved for incident analysis.
     *
     * Per docs/agent-rules/review-baseline.md §1, this is the "non-transactional
     * cleanup" exception to the no-catch-Exception rule.
     */
    @Override
    public void rollbackResource(PluginResource resource) {
        resourceLifecycle().rollbackResource(resource);
    }

    @Override
    public void restoreResource(PluginResource resource) {
        resourceLifecycle().restoreResource(resource);
    }

    @Override
    public int pruneStaleMenus(String pluginPid, String importId, Long tenantId,
                               java.util.Collection<String> configuredMenuCodes) {
        return resourceLifecycle().pruneStaleMenus(pluginPid, importId, tenantId, configuredMenuCodes);
    }

    @Override
    public int deactivateAllPluginMenus(String pluginPid, Long tenantId) {
        return resourceLifecycle().deactivateAllPluginMenus(pluginPid, tenantId);
    }

    void registerModelCacheRollbackEviction(String modelCode) {
        fieldsModelsBindings().registerModelCacheRollbackEviction(modelCode);
    }

    int reconcileDeclaredRolesStrictly(PluginManifestExtended manifest, Long tenantId) {
        return accessPolicies().reconcileDeclaredRolesStrictly(manifest, tenantId);
    }

    void updateRolePermissions(Long roleId, RoleDefinitionDTO dto, Long tenantId, String pluginPid,
                               boolean strictResolution) {
        accessPolicies().updateRolePermissions(roleId, dto, tenantId, pluginPid, strictResolution);
    }

    static String modelCodeOfGeneratedActionPermission(String permCode) {
        return PluginAccessPolicyImporter.modelCodeOfGeneratedActionPermission(permCode);
    }

    private PluginModelFieldImporter fieldsModelsBindings() {
        return new PluginModelFieldImporter(
                this,
                metaModelService,
                metaFieldService,
                schemaManagementService,
                fieldBindingMapper,
                objectMapper,
                metaModelMapper,
                metaFieldMapper,
                extensionConverter,
                () -> cacheManager,
                log,
                sharedSupport());
    }

    private PluginCommandRuleImporter commandsBindingRules() {
        return new PluginCommandRuleImporter(
                this,
                commandService,
                commandDefinitionMapper,
                bindingRuleMapper,
                commandMetadataCache,
                log,
                sharedSupport());
    }

    private PluginAccessPolicyImporter accessPolicies() {
        return new PluginAccessPolicyImporter(
                this,
                permissionService,
                dataScopeService,
                rolePermissionService,
                roleService,
                userRoleService,
                roleMapper,
                usageIndexServiceProvider,
                metaModelMapper,
                permissionMapper,
                rolePermissionMapper,
                pluginRecordMapper,
                log,
                sharedSupport());
    }

    private PluginPageNavigationImporter pageNavigationResources() {
        return new PluginPageNavigationImporter(
                this,
                dictService,
                menuService,
                pageSchemaService,
                dashboardService,
                environmentService,
                menuMapper,
                objectMapper,
                pageSchemaMapper,
                dictMapper,
                menuCodeToIdMap,
                log,
                sharedSupport());
    }

    private PluginNamedQueryImporter namedQueries() {
        return new PluginNamedQueryImporter(
                this,
                namedQueryService,
                objectMapper,
                namedQueryMapper,
                log,
                sharedSupport());
    }

    private PluginResourceLifecycleImporter resourceLifecycle() {
        return new PluginResourceLifecycleImporter(
                this,
                metaModelService,
                metaFieldService,
                dictService,
                commandService,
                permissionService,
                menuService,
                pageSchemaService,
                namedQueryService,
                jdbcTemplate,
                roleMapper,
                menuMapper,
                metaModelMapper,
                commandDefinitionMapper,
                permissionMapper,
                pageSchemaMapper,
                dictMapper,
                namedQueryMapper,
                agentDefinitionResources,
                metaFieldMapper,
                pluginResourceMapper,
                publicPublicationLifecycle,
                log,
                sharedSupport());
    }

    private PluginImportSupport sharedSupport() {
        return new PluginImportSupport(
                jdbcTemplate,
                objectMapper,
                pluginResourceMapper,
                log);
    }
}
