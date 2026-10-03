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
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, source_type, "
                        + "is_current, status, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'ab_object_alias', 'ab_object_alias', 'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), UniqueIdGenerator.generate(), tenant.getId());
        jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                        + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                        + "VALUES (?, ?, 'permission_fixture', 'Permission fixture', 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                UniqueIdGenerator.generate(), tenant.getId(), user.getId(), user.getId());
        modelPid = publisher.publishFromYaml(YAML.getBytes(StandardCharsets.UTF_8), "permission-fixture", tenant.getId(), user.getId());
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
        Map<String, String> state = new LinkedHashMap<>();
        for (String table : List.of("ab_semantic_model", "ab_semantic_metric", "ab_semantic_dimension",
                "ab_semantic_metric_alert", "ab_semantic_preagg", "ab_semantic_query_log", "ab_notification")) {
            state.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM "
                    + table + " t WHERE tenant_id = ?", String.class, tenant.getId()));
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
        RolePermission grant = new RolePermission();
        grant.setPid(UniqueIdGenerator.generate());
        grant.setRoleId(role.getId());
        grant.setPermissionId(register(MetaPermission.META_SEMANTIC_USE).getId());
        grant.setTenantId(tenant.getId());
        grant.setGrantType("grant");
        grant.setStatus("active");
        grant.setDeletedFlag(false);
        grant.setCreatedAt(Instant.now());
        grant.setUpdatedAt(Instant.now());
        rolePermissions.insert(grant);
        userPermissions.evictRoleUsers(tenant.getId(), role.getId());
        assertThat(userPermissions.hasPermission(user.getId(), MetaPermission.META_SEMANTIC_USE)).isTrue();
        assertThat(adminRoles.hasRole(tenant.getId(), user.getId(), RoleCodes.TENANT_ADMIN)).isFalse();
        mvc.perform(MockMvcRequestBuilders.get("/api/semantic/meta"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.models[0].pid").value(modelPid))
                .andExpect(jsonPath("$.data.models[0].metrics[0].code").value("count"));
        mvc.perform(MockMvcRequestBuilders.post("/api/semantic/publish").contentType("application/json")
                .content(json.writeValueAsString(Map.of("yaml", YAML, "pluginCode", "permission-fixture"))))
                .andExpect(status().isForbidden());
    }
}
