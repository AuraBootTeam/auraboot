package com.auraboot.framework.bi.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer guard + behavior IT for the additive report-definition CRUD API
 * ({@code /api/report-definitions}, Phase 4 slice 2a).
 *
 * <p>Mirrors {@code KnowledgeBaseControllerIntegrationTest}: each endpoint has a PERMIT case (the
 * user holds the required code → 2xx + correct body) and a DENY case (the user lacks it → the
 * {@code @RequirePermission} interceptor throws {@code AccessDeniedException} which
 * {@code GlobalExceptionHandler} maps to HTTP 403 — the platform convention for permission denials
 * in the {@code webAppContextSetup} harness; the same status every other permission IT asserts).
 *
 * <p>Behavior coverage: create→get round-trips the dsl as a real JSON object; update mutates and
 * bumps version; delete then get is 404; list is tenant-scoped (a second tenant's report is not
 * returned) and excludes the full dsl.
 */
class ReportDefinitionControllerIT extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private ReportStorageService reportStorageService;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails ud = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"), true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*").build();
    }

    // ---------- DENY: without grants every endpoint is forbidden ----------

    @Test
    @DisplayName("DENY: without grants every endpoint is 403 (create/update/get/list/delete)")
    void withoutGrants_allEndpointsForbidden() throws Exception {
        userPermissionService.evictUserPermissions(getTestUser().getId());

        mockMvc.perform(post("/api/report-definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"r\",\"title\":\"t\",\"dsl\":{}}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/report-definitions/SOMEPID").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dsl\":{}}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/report-definitions/SOMEPID")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/report-definitions")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/report-definitions/SOMEPID")).andExpect(status().isForbidden());
    }

    // ---------- PERMIT: create round-trips dsl as a real object, mints pid ----------

    @Test
    @DisplayName("PERMIT create: REPORT_MANAGE → 201-ish 2xx, mints pid, dsl round-trips as object")
    void create_withManage_mintsPid_andRoundTripsDsl() throws Exception {
        grantManage();
        grantRead();

        String body = "{\"code\":\"q3-sales\",\"title\":\"Q3 Sales\",\"profile\":\"paged-media\","
                + "\"dsl\":{\"sections\":[{\"type\":\"table\",\"bind\":\"orders\"}],\"page\":\"A4\"}}";

        String created = mockMvc.perform(post("/api/report-definitions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pid").isNotEmpty())
                .andExpect(jsonPath("$.data.code").value("q3-sales"))
                .andExpect(jsonPath("$.data.title").value("Q3 Sales"))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.version").value(1))
                // dsl comes back as a real JSON object (not an escaped string)
                .andExpect(jsonPath("$.data.dsl.page").value("A4"))
                .andExpect(jsonPath("$.data.dsl.sections[0].type").value("table"))
                .andReturn().getResponse().getContentAsString();

        String pid = objectMapper.readTree(created).path("data").path("pid").asText();

        // GET round-trips the same dsl object
        mockMvc.perform(get("/api/report-definitions/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dsl.page").value("A4"))
                .andExpect(jsonPath("$.data.dsl.sections[0].bind").value("orders"));
    }

    @Test
    @DisplayName("DENY create: REPORT_READ only (no MANAGE) → 403")
    void create_withReadOnly_forbidden() throws Exception {
        grantRead();
        mockMvc.perform(post("/api/report-definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"r\",\"title\":\"t\",\"dsl\":{}}"))
                .andExpect(status().isForbidden());
    }

    // ---------- PERMIT: update mutates + bumps version ----------

    @Test
    @DisplayName("PERMIT update: REPORT_MANAGE → mutates title/dsl, bumps version")
    void update_withManage_mutates() throws Exception {
        grantManage();
        grantRead();
        String pid = seedReport(getTestTenant().getId(), "upd-code", "Old Title", "{\"v\":1}");

        mockMvc.perform(put("/api/report-definitions/" + pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\",\"dsl\":{\"v\":2,\"extra\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("New Title"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.dsl.v").value(2))
                .andExpect(jsonPath("$.data.dsl.extra").value(true));

        mockMvc.perform(get("/api/report-definitions/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("New Title"))
                .andExpect(jsonPath("$.data.dsl.v").value(2));
    }

    @Test
    @DisplayName("DENY update: REPORT_READ only → 403")
    void update_withReadOnly_forbidden() throws Exception {
        grantRead();
        String pid = seedReport(getTestTenant().getId(), "upd-deny", "T", "{}");
        mockMvc.perform(put("/api/report-definitions/" + pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dsl\":{}}"))
                .andExpect(status().isForbidden());
    }

    // ---------- PERMIT: get one; not-found is 404 ----------

    @Test
    @DisplayName("PERMIT get: REPORT_READ → 200 for live, 404 for unknown")
    void get_withRead_okForLive_404ForUnknown() throws Exception {
        grantRead();
        String pid = seedReport(getTestTenant().getId(), "get-code", "G", "{\"k\":\"v\"}");

        mockMvc.perform(get("/api/report-definitions/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("get-code"))
                .andExpect(jsonPath("$.data.dsl.k").value("v"));

        mockMvc.perform(get("/api/report-definitions/NO-SUCH-PID"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DENY get: no grants → 403")
    void get_withoutGrants_forbidden() throws Exception {
        String pid = seedReport(getTestTenant().getId(), "get-deny", "G", "{}");
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mockMvc.perform(get("/api/report-definitions/" + pid)).andExpect(status().isForbidden());
    }

    // ---------- PERMIT: delete → subsequent get is 404 ----------

    @Test
    @DisplayName("PERMIT delete: REPORT_MANAGE → soft-deletes, then get is 404")
    void delete_withManage_softDeletes_thenGet404() throws Exception {
        grantManage();
        grantRead();
        String pid = seedReport(getTestTenant().getId(), "del-code", "D", "{}");

        mockMvc.perform(delete("/api/report-definitions/" + pid)).andExpect(status().isOk());
        mockMvc.perform(get("/api/report-definitions/" + pid)).andExpect(status().isNotFound());
        // second delete is now 404 (already soft-deleted)
        mockMvc.perform(delete("/api/report-definitions/" + pid)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DENY delete: REPORT_READ only → 403")
    void delete_withReadOnly_forbidden() throws Exception {
        grantRead();
        String pid = seedReport(getTestTenant().getId(), "del-deny", "D", "{}");
        mockMvc.perform(delete("/api/report-definitions/" + pid)).andExpect(status().isForbidden());
    }

    // ---------- PERMIT: list is tenant-scoped + excludes dsl ----------

    @Test
    @DisplayName("PERMIT list: tenant-scoped (other tenant excluded) and excludes full dsl")
    void list_isTenantScoped_andExcludesDsl() throws Exception {
        grantRead();

        String minePid = seedReport(getTestTenant().getId(), "mine-code", "Mine",
                "{\"big\":\"DSL_PAYLOAD_SHOULD_NOT_APPEAR_IN_LIST\"}");
        long otherTenantId = getTestTenant().getId() + 999_999L;
        seedReport(otherTenantId, "other-code", "OtherTenant", "{\"x\":1}");

        String listBody = mockMvc.perform(get("/api/report-definitions"))
                .andExpect(status().isOk())
                // my report appears
                .andExpect(jsonPath("$.data[?(@.code=='mine-code')]").isNotEmpty())
                // the other tenant's report does NOT appear
                .andExpect(jsonPath("$.data[?(@.code=='other-code')]").isEmpty())
                .andReturn().getResponse().getContentAsString();

        // list summary rows carry pid/code/title/status but NOT the dsl payload
        assertThat(listBody).contains("mine-code");
        assertThat(listBody).doesNotContain("DSL_PAYLOAD_SHOULD_NOT_APPEAR_IN_LIST");
        assertThat(listBody).doesNotContain("\"dsl\"");
        assertThat(minePid).isNotBlank();
    }

    @Test
    @DisplayName("DENY list: no grants → 403")
    void list_withoutGrants_forbidden() throws Exception {
        userPermissionService.evictUserPermissions(getTestUser().getId());
        mockMvc.perform(get("/api/report-definitions")).andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    /** Insert a live report for an arbitrary tenant via the slice-1 storage service. */
    private String seedReport(Long tenantId, String code, String title, String dsl) {
        // The storage create() reads no MetaContext — it takes the tenant on the entity — so we can
        // seed a second tenant's row directly without flipping the request's thread-local context.
        ReportEntity e = new ReportEntity();
        e.setTenantId(tenantId);
        e.setCode(code);
        e.setTitle(title);
        e.setDsl(dsl);
        e.setCreatedBy(getTestUser().getId());
        e.setUpdatedBy(getTestUser().getId());
        return reportStorageService.create(e).getPid();
    }

    private void grantManage() {
        grant(MetaPermission.REPORT_MANAGE, "meta", "template", "update", "Report Manage");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grantRead() {
        grant(MetaPermission.REPORT_READ, "meta", "template", "read", "Report Read");
        userPermissionService.evictUserPermissions(getTestUser().getId());
    }

    private void grant(String code, String resourceType, String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }
        boolean notAssigned = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)
        ).isEmpty();
        if (notAssigned) {
            RolePermission rp = new RolePermission();
            rp.setPid(UniqueIdGenerator.generate());
            rp.setRoleId(getTestRole().getId());
            rp.setPermissionId(permission.getId());
            rp.setGrantType("grant");
            rp.setStatus("active");
            rp.setDeletedFlag(false);
            rp.setTenantId(getTestTenant().getId());
            rp.setCreatedAt(Instant.now());
            rp.setUpdatedAt(Instant.now());
            rolePermissionMapper.insert(rp);
        }
    }
}
