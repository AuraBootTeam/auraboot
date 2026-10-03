package com.auraboot.framework.organization.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real permission interceptor, imported organization DSL, and persisted department writes. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrgControllerDepartmentPermissionIT extends BaseIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private PluginImportService imports;
    @Autowired private DynamicDataService data;
    @Autowired private UserPermissionService permissions;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String departmentPid;
    private Path plugin;

    @BeforeEach
    void configureRealOrganizationFixture() {
        applyTestMetaContext();
        plugin = Path.of(System.getProperty("user.dir"), "../plugins/org-management").normalize();
        var preview = imports.parseDirectory(plugin.toString());
        assertThat(preview.isValid()).as("organization import: %s", preview.getErrors()).isTrue();
        var imported = imports.execute(preview.getImportId(), ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true).autoPublishFields(true).autoPublishCommands(true)
                .autoPublishPages(true).build());
        assertThat(imported.isSuccess()).as("organization import: %s", imported.getErrorMessage()).isTrue();
        assertThat(permissions.getUserPermissionCodes(getTestUser().getId()))
                .doesNotContain("org.hr.manage", "org.team.manage");
        departmentPid = data.create("org_department", Map.of(
                "org_dept_name", "Department permission fixture " + System.nanoTime(),
                "org_dept_order", 5)).get("pid").toString();

        Filter identity = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                var principal = new CustomUserDetails(getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(), AuthorityUtils.NO_AUTHORITIES,
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(identity, "/*").build();
        MetaContext.clear();
    }

    @Test
    void hrCapabilityWithoutTeamManagementCanPersistAllDepartmentWrites() throws Exception {
        grantCapability("org.cap.hr");
        applyTestMetaContext();
        assertThat(permissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("org.hr.manage").doesNotContain("org.team.manage");
        var position = data.create("org_position", Map.of("org_pos_name", "Commander fixture",
                "org_pos_dept_id", departmentPid, "org_pos_level", "staff"));
        String employee = data.create("org_employee", Map.of("org_emp_name", "Commander fixture",
                "org_emp_dept_id", departmentPid, "org_emp_position_id", position.get("pid")))
                .get("pid").toString();
        String createdName = "Created with HR only " + System.nanoTime();
        var created = mvc.perform(body(post("/api/org/departments"), Map.of("org_dept_name", createdName)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andReturn();
        String pid = json.readTree(created.getResponse().getContentAsString()).path("data").path("pid").asText();
        assertThat(pid).isNotBlank();
        assertThat(department(pid).get("org_dept_name")).isEqualTo(createdName);
        mvc.perform(body(put("/api/org/departments/{pid}", pid), Map.of("org_dept_name", "Updated HR department")))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0"));
        mvc.perform(body(post("/api/org/departments/sort"), Map.of("items", List.of(Map.of("pid", pid, "order", 17)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        mvc.perform(body(post("/api/org/departments/{pid}/set-commander", pid), Map.of("employeePid", employee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        var persisted = department(pid);
        assertThat(persisted.get("org_dept_name")).isEqualTo("Updated HR department");
        assertThat(((Number) persisted.get("org_dept_order")).intValue()).isEqualTo(17);
        assertThat(persisted.get("org_dept_manager_id").toString()).isEqualTo(employee);
        mvc.perform(delete("/api/org/departments/{pid}", pid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mt_org_department WHERE tenant_id = ? AND pid = ?",
                Long.class, getTestTenant().getId(), pid)).isZero();
    }

    @Test
    void hrReaderCannotWriteDepartments() throws Exception {
        grantCapability("org.cap.hr_view");
        assertDepartmentWritesDenied();
    }

    @Test
    void teamManagerCannotWriteDepartments() throws Exception {
        grantCapability("org.cap.team");
        assertDepartmentWritesDenied();
    }

    private void assertDepartmentWritesDenied() throws Exception {
        var before = department(departmentPid);
        long count = jdbc.queryForObject("SELECT count(*) FROM mt_org_department WHERE tenant_id = ?",
                Long.class, getTestTenant().getId());
        var requests = List.of(
                body(post("/api/org/departments"), Map.of("org_dept_name", "Denied department")),
                body(put("/api/org/departments/{pid}", departmentPid), Map.of("org_dept_name", "Denied update")),
                body(post("/api/org/departments/sort"), Map.of("items", List.of(Map.of("pid", departmentPid, "order", 99)))),
                body(post("/api/org/departments/{pid}/set-commander", departmentPid), Map.of("employeePid", "not-an-employee")),
                delete("/api/org/departments/{pid}", departmentPid));
        for (var request : requests) {
            mvc.perform(request).andExpect(status().isForbidden());
            assertThat(department(departmentPid)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mt_org_department WHERE tenant_id = ?",
                    Long.class, getTestTenant().getId())).isEqualTo(count);
        }
    }

    private void grantCapability(String capabilityCode) throws Exception {
        var declarations = json.readTree(Files.readString(plugin.resolve("config/capabilities.json")));
        var matches = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        declarations.forEach(capability -> {
            if (capabilityCode.equals(capability.path("code").asText())) matches.add(capability);
        });
        assertThat(matches).as("source capability %s", capabilityCode).hasSize(1);
        assertThat(matches.get(0).path("includes").size()).isPositive();
        for (var include : matches.get(0).path("includes")) {
            String code = include.asText();
            int separator = code.lastIndexOf('.');
            String action = separator < 0 ? "access" : code.substring(separator + 1);
            String resource = code.startsWith("model.") ? code.substring(6, separator) : "org";
            grantCommittedPermissionToTestRole(code, code.startsWith("model.") ? "model" : "organization",
                    resource, action, code);
        }
    }

    private Map<String, Object> department(String pid) {
        return jdbc.queryForMap("SELECT * FROM mt_org_department WHERE tenant_id = ? AND pid = ?",
                getTestTenant().getId(), pid);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private ResultMatcher success() {
        return result -> assertThat(result.getResponse().getStatus())
                .as("%s %s: %s; exception=%s", result.getRequest().getMethod(),
                        result.getRequest().getRequestURI(), result.getResponse().getContentAsString(),
                        result.getResolvedException())
                .isEqualTo(200);
    }
}
