package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.semantic.service.SemanticPublishService;
import com.auraboot.framework.semantic.service.SemanticMetricAlertService;
import com.auraboot.framework.semantic.service.SemanticPreaggService;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API / real-stack / IT / MockMvc. Authentication is a fixture filter; real MVC
 * dispatch, permission interception and PostgreSQL/Redis permission reads remain
 * intact. This is not a browser/login or live-network acceptance test. Each case
 * retains a fresh tenant and ordinary user, without changing shared identities.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SemanticControllerPermissionIT extends BaseIntegrationTest {
    @Autowired private WebApplicationContext webContext;
    @Autowired private UserService users;
    @Autowired private TenantService tenants;
    @Autowired private TenantMemberService members;
    @Autowired private PermissionMapper permissions;
    @Autowired private UserPermissionService userPermissions;
    @Autowired private AdminRoleChecker adminRoles;
    @Autowired private RoleService roles;
    @Autowired private UserRoleService userRoles;
    @Autowired private RolePermissionMapper rolePermissions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private SemanticPublishService publisher;
    @Autowired private SemanticMetricAlertService alerts;
    @Autowired private SemanticPreaggService preaggs;
    @Autowired private AbSemanticMetricMapper metrics;
    @Autowired private com.auraboot.framework.semantic.service.SemanticQueryService queries;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService sourceModels;
    @Autowired private com.auraboot.framework.meta.service.FieldMaskService fieldMasks;
    @Autowired private com.auraboot.framework.meta.service.DataPermissionPolicyService columnPolicies;
    @Autowired private com.auraboot.framework.permission.service.RolePermissionService rolePermissionService;
    @Autowired private com.auraboot.framework.meta.mapper.AuditTrailMapper protectionAudits;
    @Autowired private com.auraboot.framework.meta.service.impl.AuditTrailService auditTrails;
    private String modelPid;
    private String metricPid;
    private String alertPid;
    private String preaggPid;
    static final String YAML = """
            version: "0.1"
            semantic_model:
              code: permission_fixture
              label:
                en-US: Permission Fixture
              model_ref: ab_object_alias
              primary_entity: pid
            entities:
              - name: pid
                type: primary
                field_ref: pid
            dimensions:
              - code: alias_language
                label:
                  en-US: Language
                field_ref: language
                type: categorical
            measures:
              - code: aliases
                label:
                  en-US: Aliases
                agg: COUNT
                field_ref: pid
            metrics:
              - code: count
                label:
                  en-US: Count
                type: simple
                type_params:
                  measure: aliases
            """;
    private Tenant tenant;
    private User user;
    private TenantMember member;
    private MockMvc mvc;

    @BeforeEach
    void ordinaryIdentity() {
        String run = UUID.randomUUID().toString();
        user = users.signUp("semantic-deny-" + run + "@example.test", "SemanticFixture2026!", "Semantic ordinary fixture", "semantic-deny-" + run);
        Tenant draft = new Tenant();
        draft.setPid(UniqueIdGenerator.generate());
        draft.setName("semantic-deny-" + run);
        draft.setDisplayName("Semantic permission fixture");
        draft.setStatus("active");
        draft.setDeletedFlag(false);
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        tenant = tenants.createTenant(draft);
        bindOrdinaryContext();
        member = members.addMember(user.getId(), tenant.getId(), "active");
        bindOrdinaryContext();
        seedExistingArtifacts();
        bindOrdinaryContext();
        register(MetaPermission.META_SEMANTIC_USE);
        register(MetaPermission.META_SEMANTIC_PUBLISH);
        userPermissions.evictPermissionDefinitions(tenant.getId());
        userPermissions.evictUserPermissions(tenant.getId(), user.getId());
        adminRoles.invalidateAll();
        assertThat(adminRoles.hasRole(tenant.getId(), user.getId(), RoleCodes.TENANT_ADMIN)).isFalse();
        assertThat(adminRoles.hasRole(tenant.getId(), user.getId(), RoleCodes.PLATFORM_ADMIN)).isFalse();
        assertThat(userPermissions.hasPermission(user.getId(), MetaPermission.META_SEMANTIC_USE)).isFalse();
        assertThat(userPermissions.hasPermission(user.getId(), MetaPermission.META_SEMANTIC_PUBLISH)).isFalse();
        Filter authenticatedFixture = (request, response, chain) -> {
            MetaContext.Snapshot previous = MetaContext.snapshot();
            var previousSecurity = SecurityContextHolder.getContext();
            try {
                bindOrdinaryContext();
                var details = new CustomUserDetails(user.getUserName(), "fixture", user.getId(), user.getPid(),
                        AuthorityUtils.createAuthorityList("role_user"), true, true, true, true);
                details.setMemberId(member.getId());
                var security = SecurityContextHolder.createEmptyContext();
                security.setAuthentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
                SecurityContextHolder.setContext(security);
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.setContext(previousSecurity);
                MetaContext.clear();
                MetaContext.restore(previous);
            }
        };
        mvc = MockMvcBuilders.webAppContextSetup(webContext).addFilter(authenticatedFixture, "/*").build();
    }

    private void bindOrdinaryContext() {
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getUserName());
        if (member != null) MetaContext.setMemberId(member.getId());
    }

    private void seedExistingArtifacts() {
        seedExistingArtifacts(YAML);
    }

    private void seedExistingArtifacts(String yaml) {
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, source_type, "
                        + "is_current, status, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'ab_object_alias', 'ab_object_alias', 'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), UniqueIdGenerator.generate(), tenant.getId());
        jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                        + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                        + "VALUES (?, ?, 'permission_fixture', 'Permission fixture', 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                UniqueIdGenerator.generate(), tenant.getId(), user.getId(), user.getId());
        modelPid = publisher.publishFromYaml(yaml.getBytes(StandardCharsets.UTF_8), "permission-fixture", tenant.getId(), user.getId());
        bindOrdinaryContext();
        metricPid = metrics.findByCode(tenant.getId(), "count", "0.1").getPid();
        SemanticMetricAlertRequest alert = new SemanticMetricAlertRequest();
        alert.setName("Existing permission fixture");
        alert.setMetricPid(metricPid);
        alert.setComparator("gte");
        alert.setThreshold(BigDecimal.ONE);
        alert.setSilenceMinutes(0);
        alert.setAlertStatus("paused");
        alertPid = alerts.create(alert).getPid();
        preaggPid = preaggs.create("Existing permission fixture", modelPid, "count", List.of(), 60).getPid();
    }

    private Permission register(String code) {
        Permission existing = permissions.findByCode(code);
        if (existing != null) return existing;
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(tenant.getId());
        permission.setCode(code);
        permission.setName(code);
        permission.setResourceType("meta");
        permission.setResourceCode("semantic");
        permission.setAction(code.substring(code.lastIndexOf('.') + 1));
        permission.setSource("test");
        permission.setStatus("active");
        permission.setDeletedFlag(false);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permissions.insert(permission);
        return permission;
    }

    record Endpoint(String method, String path, String mediaType, String body, Class<?> controller, String handler) {}

    static Stream<Endpoint> endpoints() {
        Stream.Builder<Endpoint> cases = Stream.builder();
        for (String action : List.of("query", "sql")) {
            cases.add(new Endpoint("POST", "/api/semantic/" + action, "application/json", "{\"metrics\":[\"permission_fixture.count\"]}",
                    SemanticController.class, action.equals("query") ? "query" : "explain"));
        }
        for (String action : List.of("validate", "publish")) {
            String path = "/api/semantic/" + action + (action.equals("publish") ? "?pluginCode=permission-fixture" : "");
            for (String media : List.of("application/yaml", "text/yaml", "text/plain")) {
                cases.add(new Endpoint("POST", path, media, "@yaml", SemanticController.class, action));
            }
            cases.add(new Endpoint("POST", "/api/semantic/" + action, "application/json",
                    "@yaml-json", SemanticController.class, action + "Json"));
        }
        cases.add(new Endpoint("GET", "/api/semantic/meta", null, null, SemanticController.class, "meta"));
        cases.add(new Endpoint("GET", "/api/semantic/lineage/@modelPid", null, null, SemanticController.class, "lineage"));
        cases.add(new Endpoint("GET", "/api/semantic/usage/summary?days=7", null, null, SemanticUsageController.class, "summary"));
        String alert = "{\"name\":\"permission-fixture\",\"metricPid\":\"@metricPid\",\"comparator\":\"gte\",\"threshold\":1,\"silenceMinutes\":0}";
        cases.add(new Endpoint("GET", "/api/semantic/alerts", null, null, SemanticMetricAlertController.class, "list"));
        cases.add(new Endpoint("POST", "/api/semantic/alerts", "application/json", alert, SemanticMetricAlertController.class, "create"));
        cases.add(new Endpoint("PUT", "/api/semantic/alerts/@alertPid", "application/json", alert, SemanticMetricAlertController.class, "update"));
        cases.add(new Endpoint("DELETE", "/api/semantic/alerts/@alertPid", null, null, SemanticMetricAlertController.class, "delete"));
        cases.add(new Endpoint("POST", "/api/semantic/alerts/@alertPid/evaluate", null, null, SemanticMetricAlertController.class, "evaluate"));
        cases.add(new Endpoint("GET", "/api/semantic/preaggs", null, null, SemanticPreaggController.class, "list"));
        cases.add(new Endpoint("POST", "/api/semantic/preaggs?name=permission-fixture&semanticModelPid=@modelPid&metricCode=count", null, null, SemanticPreaggController.class, "create"));
        cases.add(new Endpoint("DELETE", "/api/semantic/preaggs/@preaggPid", null, null, SemanticPreaggController.class, "delete"));
        cases.add(new Endpoint("POST", "/api/semantic/preaggs/@preaggPid/refresh", null, null, SemanticPreaggController.class, "refresh"));
        return cases.build();
    }

    private Map<String, String> semanticSnapshot() {
        return semanticSnapshot(tenant.getId());
    }

    private Map<String, String> semanticSnapshot(Long tenantId) {
        Map<String, String> state = new LinkedHashMap<>();
        for (String table : List.of("ab_semantic_model", "ab_semantic_metric", "ab_semantic_dimension",
                "ab_semantic_lineage_edge", "ab_semantic_metric_alert", "ab_semantic_preagg", "ab_semantic_query_log", "ab_notification")) {
            state.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM "
                    + table + " t WHERE tenant_id = ?", String.class, tenantId));
        }
        state.put("materializedViews", jdbc.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(v) ORDER BY matviewname)::text, '[]') "
                        + "FROM pg_matviews v WHERE schemaname = 'public' AND matviewname LIKE 'mv_semantic_preagg_%'", String.class));
        return state;
    }

    private String resolve(String template) {
        return template.replace("@modelPid", modelPid).replace("@metricPid", metricPid)
                .replace("@alertPid", alertPid).replace("@preaggPid", preaggPid);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("endpoints")
    void ordinaryMemberIsDeniedWithoutSideEffects(Endpoint endpoint) throws Exception {
        Map<String, String> before = semanticSnapshot();
        var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(endpoint.method()), resolve(endpoint.path()));
        if (endpoint.mediaType() != null) request.contentType(endpoint.mediaType());
        if (endpoint.body() != null) {
            String body = switch (endpoint.body()) {
                case "@yaml" -> YAML;
                case "@yaml-json" -> json.writeValueAsString(Map.of("yaml", YAML, "pluginCode", "permission-fixture"));
                default -> resolve(endpoint.body());
            };
            request.content(body);
        }
        var result = mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist()).andReturn();
        assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
        HandlerMethod handler = (HandlerMethod) result.getHandler();
        assertThat(handler.getBeanType()).isEqualTo(endpoint.controller());
        assertThat(handler.getMethod().getName()).isEqualTo(endpoint.handler());
        assertThat(semanticSnapshot()).isEqualTo(before);
    }

    @Test
    void ordinaryMemberCanReadCatalogWithExplicitUseGrant() throws Exception {
        grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE);
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/meta"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.models[0].pid").value(modelPid))
                .andExpect(jsonPath("$.data.models[0].metrics[0].code").value("count"));
        mvc.perform(MockMvcRequestBuilders.post("/api/semantic/publish").contentType("application/json")
                .content(json.writeValueAsString(Map.of("yaml", YAML, "pluginCode", "permission-fixture"))))
                .andExpect(status().isForbidden());
    }

    private void declareProtectedSourceFields() {
        var definition = sourceModels.getDefinitionByCode("ab_object_alias");
        assertThat(definition).isNotNull();
        var declared = new java.util.ArrayList<>(definition.getFields());
        for (String column : List.of("pid", "language", "alias")) {
            declared.add(com.auraboot.framework.meta.dto.FieldDefinition.builder()
                    .code("sensitive_" + column).columnName(column).dataType("string").build());
        }
        definition.setFields(declared);
        sourceModels.saveDefinition(definition);
        assertThat(sourceModels.getColumnName("ab_object_alias", "sensitive_language")).isEqualTo("language");
    }

    private com.auraboot.framework.meta.entity.FieldMaskConfig protectSource(String field, String exemption) {
        var config = new com.auraboot.framework.meta.entity.FieldMaskConfig();
        config.setModelCode("ab_object_alias"); config.setFieldCode(field); config.setMaskType("FULL");
        config.setEnabled(true); config.setApplyToList(false); config.setApplyToDetail(false);
        config.setApplyToExport(true); config.setExemptPermissionCodes(exemption);
        return fieldMasks.saveConfig(config);
    }

    private String protectedRequest(boolean dimension) throws Exception {
        return json.writeValueAsString(dimension
                ? Map.of("metrics", List.of("permission_fixture.count"), "dimensions", List.of("alias_language"))
                : Map.of("metrics", List.of("permission_fixture.count")));
    }

    private long auditSequence() {
        Long sequence = protectionAudits.getMaxSequenceNo(tenant.getId());
        return sequence == null ? 0 : sequence;
    }

    private com.auraboot.framework.meta.entity.AuditTrail newProtectionAudit(long previous, String verdict) {
        var records = protectionAudits.getBySequenceRange(tenant.getId(), previous + 1, Long.MAX_VALUE);
        assertThat(records).hasSize(1);
        var row = records.get(0);
        assertThat(row.getEventType()).isEqualTo("SEMANTIC_QUERY_PROTECTION");
        assertThat(row.getActorId()).isEqualTo(user.getId());
        assertThat(row.getTenantId()).isEqualTo(tenant.getId());
        assertThat(row.getEntityPid()).isNotBlank();
        var metadata = row.getMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.path("format").asText()).isEqualTo("auraboot.semantic.protection.v1");
        assertThat(metadata.path("phase").asText()).isEqualTo("admission");
        assertThat(metadata.path("semanticModelCode").asText()).isEqualTo("permission_fixture");
        assertThat(metadata.path("sqlFingerprint").asText()).matches("[a-f0-9]{64}");
        assertThat(metadata.path("protectionPlan").path("sourceModelCode").asText()).isEqualTo("ab_object_alias");
        assertThat(metadata.path("protectionPlan").path("verdict").asText()).isEqualTo(verdict);
        assertThat(metadata.toString()).doesNotContain("en-US", "Permission fixture", user.getUserName());
        assertThat(metadata.has("sql") || metadata.has("params") || metadata.has("rows") || metadata.has("filters")).isFalse();
        assertThat(auditTrails.verifyChainIntegrity(tenant.getId(), row.getSequenceNo(), row.getSequenceNo()).isValid()).isTrue();
        return row;
    }

    private void queryProtected(String request, boolean allow) throws Exception {
        long sequence = auditSequence();
        var operation = mvc.perform(MockMvcRequestBuilders.post("/api/semantic/query")
                .contentType("application/json").content(request));
        if (allow) {
            var response = operation.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.rowcount").value(1))
                    .andExpect(jsonPath("$.data.rows[0]['permission_fixture.count']").value(1)).andReturn();
            var queryId = json.readTree(response.getResponse().getContentAsString()).path("data").path("queryId").asText();
            assertThat(newProtectionAudit(sequence, "ALLOW").getEntityPid()).isEqualTo(queryId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ? AND query_id = ?",
                    Long.class, tenant.getId(), queryId)).isEqualTo(1);
        } else {
            operation.andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
            var row = newProtectionAudit(sequence, "DENY");
            assertThat(row.getMetadata().path("protectionPlan").path("reason").asText()).isEqualTo("PROTECTED_SOURCE_COLUMN");
        }
    }

    @ParameterizedTest(name = "field-mask {0}")
    @ValueSource(strings = {"dimension", "measure-expression", "metric-filter"})
    void protectedDimensionsExpressionsAndFiltersDenyThenRecoverWithPlanAudit(String surface) throws Exception {
        grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE);
        declareProtectedSourceFields();
        String yaml = YAML;
        if (surface.equals("measure-expression")) {
            yaml = YAML.replace("    agg: COUNT\n    field_ref: pid", "    agg: COUNT\n    expr: \"CASE WHEN language = 'en-US' THEN pid ELSE NULL END\"");
            assertThat(yaml).contains("expr:");
        } else if (surface.equals("metric-filter")) {
            yaml = YAML.replace("    type: simple", "    filter: \"language = 'en-US'\"\n    type: simple");
            assertThat(yaml).contains("filter:");
        }
        publisher.publishFromYaml(yaml.getBytes(StandardCharsets.UTF_8), "permission-fixture", tenant.getId(), user.getId());
        String request = protectedRequest(surface.equals("dimension"));
        queryProtected(request, true);
        var config = protectSource("sensitive_language", null);
        Map<String, String> before = semanticSnapshot();
        queryProtected(request, false);
        assertThat(semanticSnapshot()).isEqualTo(before);
        config.setEnabled(false); fieldMasks.saveConfig(config);
        queryProtected(request, true);
    }

    @Test
    void canonicalColumnPolicyBindingDeniesAndUnbindingRecovers() throws Exception {
        Role role = grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE);
        declareProtectedSourceFields();
        String request = protectedRequest(true);
        queryProtected(request, true);
        var policy = new com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest();
        policy.setName("Semantic protected language"); policy.setModelCode("ab_object_alias");
        policy.setPolicyType("column"); policy.setFieldCode("sensitive_language"); policy.setMaskType("HIDE");
        var created = columnPolicies.create(policy);
        columnPolicies.bindToRole(created.getPid(), role.getPid());
        Map<String, String> before = semanticSnapshot();
        queryProtected(request, false);
        assertThat(semanticSnapshot()).isEqualTo(before);
        columnPolicies.unbindFromRole(created.getPid(), role.getPid());
        queryProtected(request, true);
    }

    @Test
    void exemptionGrantRevocationAndRestorationUseCanonicalPermissionServices() throws Exception {
        String exempt = "fixture.semantic.unmask";
        Role role = grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE, exempt);
        declareProtectedSourceFields();
        protectSource("sensitive_language", exempt);
        String request = protectedRequest(true);
        queryProtected(request, true);
        Permission exemption = register(exempt);
        assertThat(rolePermissionService.removePermission(role.getId(), exemption.getId())).isTrue();
        assertThat(userPermissions.hasPermission(user.getId(), MetaPermission.META_SEMANTIC_USE)).isTrue();
        assertThat(userPermissions.hasPermission(user.getId(), exempt)).isFalse();
        Map<String, String> before = semanticSnapshot();
        queryProtected(request, false);
        assertThat(semanticSnapshot()).isEqualTo(before);
        assertThat(rolePermissionService.assignPermissionsToRole(role.getId(), List.of(
                register(MetaPermission.META_SEMANTIC_USE).getId(), exemption.getId()))).isTrue();
        assertThat(userPermissions.hasPermission(user.getId(), exempt)).isTrue();
        queryProtected(request, true);
    }

    @Test
    void protectedPreaggRollbackRetainsDeniedPlanAndOriginalView() throws Exception {
        grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE, MetaPermission.META_SEMANTIC_PUBLISH);
        declareProtectedSourceFields();
        protectSource("sensitive_pid", null);
        Map<String, String> before = semanticSnapshot();
        long sequence = auditSequence();
        assertThatThrownBy(() -> preaggs.refreshNow(preaggPid)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(semanticSnapshot()).isEqualTo(before);
        assertThat(newProtectionAudit(sequence, "DENY").getOperationType()).isEqualTo("EXPLAIN");
        String mv = jdbc.queryForObject("SELECT mv_name FROM ab_semantic_preagg WHERE tenant_id = ? AND pid = ?",
                String.class, tenant.getId(), preaggPid);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + mv, Long.class)).isEqualTo(1L);
    }

    private Role grantOrdinaryPermissions(String... codes) {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("Semantic reader fixture");
        role.setCode("semantic-reader-" + UUID.randomUUID());
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(tenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        role = roles.createRole(role);
        assertThat(userRoles.assignRolesToMember(member.getId(), List.of(role.getId()), tenant.getId(), null)).isTrue();
        for (String code : codes) {
            RolePermission grant = new RolePermission();
            grant.setPid(UniqueIdGenerator.generate());
            grant.setRoleId(role.getId());
            grant.setPermissionId(register(code).getId());
            grant.setTenantId(tenant.getId());
            grant.setGrantType("grant");
            grant.setStatus("active");
            grant.setDeletedFlag(false);
            grant.setCreatedAt(Instant.now());
            grant.setUpdatedAt(Instant.now());
            rolePermissions.insert(grant);
        }
        userPermissions.evictRoleUsers(tenant.getId(), role.getId());
        bindOrdinaryContext();
        for (String code : codes) assertThat(userPermissions.hasPermission(user.getId(), code)).isTrue();
        assertThat(adminRoles.hasRole(tenant.getId(), user.getId(), RoleCodes.TENANT_ADMIN)).isFalse();
        assertThat(adminRoles.hasRole(tenant.getId(), user.getId(), RoleCodes.PLATFORM_ADMIN)).isFalse();
        return role;
    }

    @ParameterizedTest(name = "authorized {index}: {0}")
    @MethodSource("endpoints")
    void ordinaryMemberReceivesExactBusinessResultWithExplicitGrants(Endpoint endpoint) throws Exception {
        grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE, MetaPermission.META_SEMANTIC_PUBLISH);
        String requestYaml = endpoint.handler().startsWith("publish")
                ? YAML.replace("code: permission_fixture", "code: authorized_publish") : YAML;
        if (endpoint.controller() == SemanticUsageController.class) {
            mvc.perform(MockMvcRequestBuilders.post("/api/semantic/query").contentType("application/json")
                    .content("{\"metrics\":[\"permission_fixture.count\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowcount").value(1));
        }
        String oldMv = jdbc.queryForObject("SELECT mv_name FROM ab_semantic_preagg WHERE tenant_id = ? AND pid = ?",
                String.class, tenant.getId(), preaggPid);
        if (endpoint.controller() == SemanticPreaggController.class && endpoint.handler().equals("refresh")) {
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'permission_fixture', 'Refresh fixture', 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    UniqueIdGenerator.generate(), tenant.getId(), user.getId(), user.getId());
            assertThat(mvValue(oldMv)).isEqualTo(1);
        }
        Map<String, String> before = semanticSnapshot();
        var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(endpoint.method()), resolve(endpoint.path()));
        if (endpoint.mediaType() != null) request.contentType(endpoint.mediaType());
        if (endpoint.body() != null) {
            String body = switch (endpoint.body()) {
                case "@yaml" -> requestYaml;
                case "@yaml-json" -> json.writeValueAsString(Map.of("yaml", requestYaml, "pluginCode", "permission-fixture"));
                default -> resolve(endpoint.body());
            };
            if (endpoint.controller() == SemanticMetricAlertController.class) {
                var authored = json.readTree(body);
                ((com.fasterxml.jackson.databind.node.ObjectNode) authored).put("alertStatus", "paused");
                body = json.writeValueAsString(authored);
            }
            request.content(body);
        }
        var result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0")).andReturn();
        assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
        var handler = (HandlerMethod) result.getHandler();
        assertThat(handler.getBeanType()).isEqualTo(endpoint.controller());
        assertThat(handler.getMethod().getName()).isEqualTo(endpoint.handler());
        var data = json.readTree(result.getResponse().getContentAsString()).path("data");
        if (endpoint.controller() == SemanticController.class) {
            switch (endpoint.handler()) {
                case "query" -> {
                    assertThat(data.path("rowcount").asInt()).isEqualTo(1);
                    assertThat(data.path("rows").size()).isEqualTo(1);
                    assertThat(data.path("rows").get(0).size()).isEqualTo(1);
                    assertThat(data.path("rows").get(0).elements().next().decimalValue()).isEqualByComparingTo("1");
                    assertThat(data.path("queryId").asText()).isNotBlank();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ? "
                                    + "AND user_id = ? AND rowcount = 1", Long.class, tenant.getId(), user.getId())).isEqualTo(1);
                }
                case "explain" -> {
                    assertThat(data.path("sql").asText()).contains("ab_object_alias");
                    List<Object> params = json.convertValue(data.path("params"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    var rows = jdbc.queryForList(data.path("sql").asText(), params.toArray());
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0)).hasSize(1);
                    assertThat(new BigDecimal(rows.get(0).values().iterator().next().toString())).isEqualByComparingTo("1");
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                case "validate", "validateJson" -> {
                    assertThat(data.path("ok").asBoolean()).isTrue();
                    assertThat(data.path("modelCode").asText()).isEqualTo("permission_fixture");
                    assertThat(data.path("metricCount").asInt()).isEqualTo(1);
                    assertThat(data.path("dimensionCount").asInt()).isEqualTo(1);
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                case "publish", "publishJson" -> {
                    assertThat(data.path("ok").asBoolean()).isTrue();
                    String publishedPid = data.path("pid").asText();
                    assertThat(publishedPid).isNotBlank().isNotEqualTo(modelPid);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_model WHERE tenant_id = ? "
                            + "AND pid = ? AND code = 'authorized_publish' AND created_by = ? AND deleted_flag = FALSE",
                            Long.class, tenant.getId(), publishedPid, user.getId())).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_metric WHERE tenant_id = ? "
                            + "AND semantic_model_pid = ? AND code = 'count' AND deleted_flag = FALSE",
                            Long.class, tenant.getId(), publishedPid)).isEqualTo(1);
                }
                case "meta" -> {
                    assertThat(data.path("models").size()).isEqualTo(1);
                    assertThat(data.path("models").get(0).path("pid").asText()).isEqualTo(modelPid);
                    assertThat(data.path("models").get(0).path("metrics").get(0).path("code").asText()).isEqualTo("count");
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                case "lineage" -> {
                    assertThat(data.path("nodePid").asText()).isEqualTo(modelPid);
                    assertThat(data.path("nodeType").asText()).isEqualTo("model");
                    assertThat(data.path("incoming").size()).isEqualTo(1);
                    assertThat(data.path("incoming").get(0).path("srcPid").asText()).isEqualTo(modelPid + ":metric:count");
                    assertThat(data.path("incoming").get(0).path("dstPid").asText()).isEqualTo(modelPid);
                    assertThat(data.path("outgoing").size()).isZero();
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                default -> throw new AssertionError("Unasserted semantic handler: " + endpoint);
            }
        } else if (endpoint.controller() == SemanticUsageController.class) {
            assertThat(data.path("days").asInt()).isEqualTo(7);
            assertThat(data.path("totalQueries").asLong()).isEqualTo(1);
            assertThat(data.path("activeUsers").asLong()).isEqualTo(1);
            assertThat(data.path("totalRows").asLong()).isEqualTo(1);
            assertThat(data.path("daily").size()).isEqualTo(1);
            assertThat(semanticSnapshot()).isEqualTo(before);
        } else if (endpoint.controller() == SemanticMetricAlertController.class) {
            switch (endpoint.handler()) {
                case "list" -> {
                    assertThat(data.size()).isEqualTo(1);
                    assertThat(data.get(0).path("pid").asText()).isEqualTo(alertPid);
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                case "create", "update" -> {
                    String pid = data.path("pid").asText();
                    assertThat(pid).isNotBlank();
                    if (endpoint.handler().equals("update")) assertThat(pid).isEqualTo(alertPid);
                    assertThat(data.path("name").asText()).isEqualTo("permission-fixture");
                    assertThat(data.path("alertStatus").asText()).isEqualTo("paused");
                    assertThat(jdbc.queryForObject("SELECT name FROM ab_semantic_metric_alert WHERE tenant_id = ? "
                            + "AND pid = ? AND deleted_flag = FALSE", String.class, tenant.getId(), pid)).isEqualTo("permission-fixture");
                }
                case "evaluate" -> {
                    assertThat(data.path("value").decimalValue()).isEqualByComparingTo("1");
                    assertThat(data.path("triggered").asBoolean()).isTrue();
                    assertThat(data.path("notified").asBoolean()).isTrue();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_notification WHERE tenant_id = ? "
                            + "AND user_id = ? AND source_type = 'semantic_metric_alert' AND source_id = ?",
                            Long.class, tenant.getId(), user.getId(), alertPid)).isEqualTo(1);
                }
                case "delete" -> {
                    assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_semantic_metric_alert WHERE tenant_id = ? AND pid = ?",
                            Boolean.class, tenant.getId(), alertPid)).isTrue();
                    mvc.perform(MockMvcRequestBuilders.get("/api/semantic/alerts")).andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.length()").value(0));
                }
                default -> throw new AssertionError("Unasserted alert handler: " + endpoint);
            }
        } else if (endpoint.controller() == SemanticPreaggController.class) {
            switch (endpoint.handler()) {
                case "list" -> {
                    assertThat(data.size()).isEqualTo(1);
                    assertThat(data.get(0).path("pid").asText()).isEqualTo(preaggPid);
                    assertThat(semanticSnapshot()).isEqualTo(before);
                }
                case "create" -> {
                    String pid = data.path("pid").asText();
                    assertThat(pid).isNotBlank().isNotEqualTo(preaggPid);
                    assertThat(data.path("dimensionCodes").asText()).isEqualTo("[]");
                    String mv = jdbc.queryForObject("SELECT mv_name FROM ab_semantic_preagg WHERE tenant_id = ? "
                            + "AND pid = ? AND deleted_flag = FALSE", String.class, tenant.getId(), pid);
                    assertThat(mvValue(mv)).isEqualTo(1);
                }
                case "refresh" -> {
                    assertThat(data.path("rows").asLong()).isEqualTo(1);
                    assertThat(mvValue(oldMv)).isEqualTo(2);
                }
                case "delete" -> {
                    assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_semantic_preagg WHERE tenant_id = ? AND pid = ?",
                            Boolean.class, tenant.getId(), preaggPid)).isTrue();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_matviews WHERE schemaname = 'public' AND matviewname = ?",
                            Long.class, oldMv)).isZero();
                    mvc.perform(MockMvcRequestBuilders.get("/api/semantic/preaggs")).andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.length()").value(0));
                }
                default -> throw new AssertionError("Unasserted preaggregation handler: " + endpoint);
            }
        } else {
            throw new AssertionError("Unasserted controller: " + endpoint);
        }
    }

    private long mvValue(String mv) {
        assertThat(mv).matches("mv_semantic_preagg_[a-z0-9]+");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + mv);
        assertThat(row).hasSize(1);
        return new BigDecimal(row.values().iterator().next().toString()).longValueExact();
    }

    record ForeignArtifacts(Long tenantId, String modelPid, String metricPid, String alertPid, String preaggPid) {}

    private ForeignArtifacts seedSecondMembership() {
        Tenant originalTenant = tenant;
        TenantMember originalMember = member;
        String originalModel = modelPid, originalMetric = metricPid, originalAlert = alertPid, originalPreagg = preaggPid;
        MetaContext.Snapshot caller = MetaContext.snapshot();
        try {
            Tenant draft = new Tenant();
            draft.setPid(UniqueIdGenerator.generate());
            draft.setName("semantic-foreign-" + UUID.randomUUID());
            draft.setDisplayName("Foreign semantic fixture");
            draft.setStatus("active");
            draft.setDeletedFlag(false);
            draft.setCreatedAt(Instant.now());
            draft.setUpdatedAt(Instant.now());
            tenant = tenants.createTenant(draft);
            member = null;
            bindOrdinaryContext();
            member = members.addMember(user.getId(), tenant.getId(), "active");
            bindOrdinaryContext();
            seedExistingArtifacts(YAML.replace("code: permission_fixture", "code: foreign_fixture"));
            bindOrdinaryContext();
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'foreign_fixture', 'Foreign extra row', 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    UniqueIdGenerator.generate(), tenant.getId(), user.getId(), user.getId());
            preaggs.refreshNow(preaggPid);
            bindOrdinaryContext();
            var request = new com.auraboot.framework.semantic.compiler.SemanticQueryRequest();
            request.setMetrics(List.of("foreign_fixture.count"));
            var response = queries.executeQuery(request,
                    new com.auraboot.framework.semantic.compiler.UserContext(user.getId(), tenant.getId(), Map.of()));
            assertThat(response.getRows()).hasSize(1);
            assertThat(new BigDecimal(response.getRows().get(0).values().iterator().next().toString())).isEqualByComparingTo("2");
            return new ForeignArtifacts(tenant.getId(), modelPid, metricPid, alertPid, preaggPid);
        } finally {
            tenant = originalTenant;
            member = originalMember;
            modelPid = originalModel;
            metricPid = originalMetric;
            alertPid = originalAlert;
            preaggPid = originalPreagg;
            MetaContext.clear();
            MetaContext.restore(caller);
        }
    }

    @Test
    void explicitGrantsDoNotCrossTheCurrentTenantBoundary() throws Exception {
        ForeignArtifacts foreign = seedSecondMembership();
        grantOrdinaryPermissions(MetaPermission.META_SEMANTIC_USE, MetaPermission.META_SEMANTIC_PUBLISH);
        var ownQuery = mvc.perform(MockMvcRequestBuilders.post("/api/semantic/query").contentType("application/json")
                .content("{\"metrics\":[\"permission_fixture.count\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowcount").value(1)).andReturn();
        var ownRows = json.readTree(ownQuery.getResponse().getContentAsString()).path("data").path("rows");
        assertThat(ownRows.size()).isEqualTo(1);
        assertThat(ownRows.get(0).size()).isEqualTo(1);
        assertThat(ownRows.get(0).elements().next().decimalValue()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ? AND rowcount = 1",
                Long.class, tenant.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ? AND rowcount = 1",
                Long.class, foreign.tenantId())).isEqualTo(1);
        Map<String, String> ownBefore = semanticSnapshot();
        Map<String, String> foreignBefore = semanticSnapshot(foreign.tenantId());
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/meta"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.models.length()").value(1))
                .andExpect(jsonPath("$.data.models[0].pid").value(modelPid));
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/alerts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pid").value(alertPid));
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/preaggs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pid").value(preaggPid));
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/usage/summary?days=7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalQueries").value(1))
                .andExpect(jsonPath("$.data.totalRows").value(1)).andExpect(jsonPath("$.data.activeUsers").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/lineage/" + foreign.modelPid()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.incoming.length()").value(0))
                .andExpect(jsonPath("$.data.outgoing.length()").value(0));
        record Rejected(String method, String path, String body, String error, Class<?> controller, String handler) {}
        String foreignAlert = json.writeValueAsString(Map.of("name", "Foreign attempt", "metricPid", foreign.metricPid(),
                "comparator", "gte", "threshold", 2, "alertStatus", "paused", "silenceMinutes", 0));
        for (Rejected rejected : List.of(
                new Rejected("POST", "/api/semantic/query", "{\"metrics\":[\"foreign_fixture.count\"]}", "UNKNOWN_METRIC", SemanticController.class, "query"),
                new Rejected("POST", "/api/semantic/sql", "{\"metrics\":[\"foreign_fixture.count\"]}", "UNKNOWN_METRIC", SemanticController.class, "explain"),
                new Rejected("POST", "/api/semantic/alerts", foreignAlert, "SEMANTIC_ALERT_METRIC_MISSING", SemanticMetricAlertController.class, "create"),
                new Rejected("PUT", "/api/semantic/alerts/" + foreign.alertPid(), foreignAlert, "SEMANTIC_ALERT_MISSING", SemanticMetricAlertController.class, "update"),
                new Rejected("DELETE", "/api/semantic/alerts/" + foreign.alertPid(), null, "SEMANTIC_ALERT_MISSING", SemanticMetricAlertController.class, "delete"),
                new Rejected("POST", "/api/semantic/alerts/" + foreign.alertPid() + "/evaluate", null, "SEMANTIC_ALERT_MISSING", SemanticMetricAlertController.class, "evaluate"),
                new Rejected("POST", "/api/semantic/preaggs?name=foreign-attempt&metricCode=count&semanticModelPid=" + foreign.modelPid(), null, "SEMANTIC_PREAGG_MODEL_MISSING", SemanticPreaggController.class, "create"),
                new Rejected("POST", "/api/semantic/preaggs/" + foreign.preaggPid() + "/refresh", null, "SEMANTIC_PREAGG_MISSING", SemanticPreaggController.class, "refresh"),
                new Rejected("DELETE", "/api/semantic/preaggs/" + foreign.preaggPid(), null, "SEMANTIC_PREAGG_MISSING", SemanticPreaggController.class, "delete"))) {
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(rejected.method()), rejected.path());
            if (rejected.body() != null) request.contentType("application/json").content(rejected.body());
            var result = mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.errorCode").value(rejected.error()))
                    .andExpect(jsonPath("$.data.rows").doesNotExist()).andReturn();
            assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
            var handler = (HandlerMethod) result.getHandler();
            assertThat(handler.getBeanType()).isEqualTo(rejected.controller());
            assertThat(handler.getMethod().getName()).isEqualTo(rejected.handler());
            assertThat(semanticSnapshot()).isEqualTo(ownBefore);
            assertThat(semanticSnapshot(foreign.tenantId())).isEqualTo(foreignBefore);
        }
        String foreignMv = jdbc.queryForObject("SELECT mv_name FROM ab_semantic_preagg WHERE tenant_id = ? AND pid = ?",
                String.class, foreign.tenantId(), foreign.preaggPid());
        assertThat(mvValue(foreignMv)).isEqualTo(2);
    }
}
