package com.auraboot.framework.organization.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
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

import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real permission interceptor, imported organization DSL, and persisted organization writes. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrgControllerPermissionIT extends BaseIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private PluginImportService imports;
    @Autowired private DynamicDataService data;
    @Autowired private UserPermissionService permissions;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserService users;
    @Autowired private TenantMemberService members;
    @Autowired private TenantService tenants;
    @Autowired private OrgEmployeeService employees;

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

    @Test
    void hrCapabilityCanCreateLinkUpdateAndTransferEmployeesWithoutTeamManagement() throws Exception {
        grantCapability("org.cap.hr");
        applyTestMetaContext();
        assertThat(permissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("org.hr.manage").doesNotContain("org.team.manage");
        String position = position(departmentPid);
        String email = "org-create-" + System.nanoTime() + "@example.test";
        var created = mvc.perform(body(post("/api/org/employees"), Map.of(
                        "name", "HR created employee", "email", email, "phone", "13800000000",
                        "deptPid", departmentPid, "positionPid", position)))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0")).andReturn();
        var dto = json.readTree(created.getResponse().getContentAsString()).path("data");
        String employeePid = dto.path("pid").asText();
        assertThat(employeePid).isNotBlank();
        var employee = employee(employeePid);
        var account = jdbc.queryForMap("SELECT id, pid FROM ab_user WHERE email = ? AND deleted_flag = false", email);
        var member = jdbc.queryForMap("SELECT pid, employee_id FROM ab_tenant_member WHERE tenant_id = ? AND user_id = ? AND deleted_flag = false",
                getTestTenant().getId(), account.get("id"));
        assertThat(employee.get("org_emp_user_id")).isEqualTo(account.get("pid"));
        assertThat(employee.get("org_emp_member_id")).isEqualTo(member.get("pid"));
        assertThat(member.get("employee_id")).isEqualTo(employee.get("id"));
        assertThat(dto.path("memberPid").asText()).isEqualTo(member.get("pid"));

        applyTestMetaContext();
        var existingUser = users.signUp("org-link-" + System.nanoTime() + "@example.test", "Test-password-2026!");
        var existingMember = members.addMember(existingUser.getId(), getTestTenant().getId(), "active");
        var linked = mvc.perform(body(post("/api/org/employees/link"), Map.of("memberPid", existingMember.getPid(),
                        "deptPid", departmentPid, "positionPid", position)))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0")).andReturn();
        String linkedPid = json.readTree(linked.getResponse().getContentAsString()).path("data").path("pid").asText();
        assertThat(linkedPid).isNotBlank();
        var linkedEmployee = employee(linkedPid);
        assertThat(linkedEmployee.get("org_emp_user_id")).isEqualTo(existingUser.getPid());
        assertThat(linkedEmployee.get("org_emp_member_id")).isEqualTo(existingMember.getPid());
        assertThat(jdbc.queryForObject("SELECT employee_id FROM ab_tenant_member WHERE tenant_id = ? AND pid = ?",
                Long.class, getTestTenant().getId(), existingMember.getPid())).isEqualTo(linkedEmployee.get("id"));

        mvc.perform(body(put("/api/org/employees/{pid}", employeePid), Map.of("org_emp_name", "Updated HR employee")))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0"));
        assertThat(employee(employeePid).get("org_emp_name")).isEqualTo("Updated HR employee");
        applyTestMetaContext();
        String target = data.create("org_department", Map.of("org_dept_name", "Transfer target " + System.nanoTime()))
                .get("pid").toString();
        String targetPosition = position(target);
        mvc.perform(body(put("/api/org/employees/{pid}/transfer", employeePid),
                        Map.of("newDeptPid", target, "newPositionPid", targetPosition)))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0"));
        assertEmployeeAssignment(employeePid, target, targetPosition);
        mvc.perform(body(put("/api/org/employees/batch-transfer"), Map.of("employeePids", List.of(employeePid, linkedPid),
                        "newDeptPid", departmentPid, "newPositionPid", position)))
                .andExpect(success()).andExpect(jsonPath("$.code").value("0"));
        assertEmployeeAssignment(employeePid, departmentPid, position);
        assertEmployeeAssignment(linkedPid, departmentPid, position);
    }

    @Test
    void hrReaderCannotWriteEmployeesOrAccounts() throws Exception {
        grantCapability("org.cap.hr_view");
        assertEmployeeWritesDenied();
    }

    @Test
    void teamManagerCannotWriteEmployeesOrAccounts() throws Exception {
        grantCapability("org.cap.team");
        assertEmployeeWritesDenied();
    }

    @Test
    void memberManagerCannotWriteOrganizationRecordsThroughNativeEmployeeEndpoints() throws Exception {
        grantCapability("org.cap.member");
        assertEmployeeWritesDenied();
    }

    @Test
    void hrManagerCannotLinkForeignTenantMemberThroughNativeEndpoint() throws Exception {
        grantCapability("org.cap.hr");
        applyTestMetaContext();
        var foreign = foreignMember();
        var before = memberRow(foreign.getPid());
        long employeeCount = employeeCount();
        String position = position(departmentPid);

        var denied = mvc.perform(body(post("/api/org/employees/link"), Map.of(
                        "memberPid", foreign.getPid(), "deptPid", departmentPid, "positionPid", position)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("404")).andReturn();

        assertThat(denied.getResponse().getContentAsString()).doesNotContain(foreign.getPid());
        assertThat(memberRow(foreign.getPid())).isEqualTo(before);
        assertThat(employeeCount()).isEqualTo(employeeCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mt_org_employee WHERE org_emp_member_id = ?",
                Long.class, foreign.getPid())).isZero();
    }

    @Test
    void existingEmployeeCannotBeLinkedToForeignTenantMember() throws Exception {
        grantCapability("org.cap.hr");
        applyTestMetaContext();
        var foreign = foreignMember();
        String employeePid = data.create("org_employee", Map.of(
                "org_emp_name", "Unlinked foreign-boundary fixture " + System.nanoTime(),
                "org_emp_dept_id", departmentPid, "org_emp_position_id", position(departmentPid))).get("pid").toString();
        var employeeBefore = employee(employeePid);
        var memberBefore = memberRow(foreign.getPid());

        var denied = assertThrows(BusinessException.class,
                () -> employees.linkExistingMember(employeePid, foreign.getPid()));

        assertThat(denied.getResponseCode()).isEqualTo(ResponseCode.NOT_FOUND);
        assertThat(memberRow(foreign.getPid())).isEqualTo(memberBefore);
        assertThat(employee(employeePid)).isEqualTo(employeeBefore);
    }

    @Test
    void existingEmployeeCanBeLinkedToOwnTenantMember() throws Exception {
        grantCapability("org.cap.hr");
        applyTestMetaContext();
        var account = users.signUp("org-existing-link-" + System.nanoTime() + "@example.test", "Test-password-2026!");
        var member = members.addMember(account.getId(), getTestTenant().getId(), "active");
        String employeePid = data.create("org_employee", Map.of(
                "org_emp_name", "Unlinked own-tenant fixture " + System.nanoTime(),
                "org_emp_dept_id", departmentPid, "org_emp_position_id", position(departmentPid))).get("pid").toString();

        var linked = employees.linkExistingMember(employeePid, member.getPid());

        assertThat(linked.pid()).isEqualTo(employeePid);
        var employee = employee(employeePid);
        assertThat(employee.get("org_emp_member_id")).isEqualTo(member.getPid());
        assertThat(employee.get("org_emp_user_id")).isEqualTo(account.getPid());
        assertThat(memberRow(member.getPid()).get("employee_id")).isEqualTo(employee.get("id"));
    }

    private TenantMember foreignMember() {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName("org-foreign-boundary-" + System.nanoTime());
        tenant.setDisplayName("Foreign organization boundary fixture");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@foreign-boundary.example.test");
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        tenant = tenants.createTenant(tenant);
        var account = users.signUp("org-foreign-member-" + System.nanoTime() + "@example.test", "Test-password-2026!");
        var member = members.addMember(account.getId(), tenant.getId(), "active");
        assertThat(member.getTenantId()).isNotEqualTo(getTestTenant().getId());
        assertThat(member.getEmployeeId()).isNull();
        return member;
    }

    private Map<String, Object> memberRow(String pid) {
        return jdbc.queryForMap("SELECT * FROM ab_tenant_member WHERE pid = ?", pid);
    }

    private long employeeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM mt_org_employee WHERE tenant_id = ?",
                Long.class, getTestTenant().getId());
    }

    private void assertEmployeeWritesDenied() throws Exception {
        applyTestMetaContext();
        String position = position(departmentPid);
        String employeePid = data.create("org_employee", Map.of("org_emp_name", "Employee denial fixture",
                "org_emp_dept_id", departmentPid, "org_emp_position_id", position)).get("pid").toString();
        var user = users.signUp("org-link-deny-" + System.nanoTime() + "@example.test", "Test-password-2026!");
        var member = members.addMember(user.getId(), getTestTenant().getId(), "active");
        var originalEmployee = employee(employeePid);
        var originalMember = jdbc.queryForMap("SELECT * FROM ab_tenant_member WHERE tenant_id = ? AND pid = ?",
                getTestTenant().getId(), member.getPid());
        long originalCount = jdbc.queryForObject("SELECT count(*) FROM mt_org_employee WHERE tenant_id = ?",
                Long.class, getTestTenant().getId());
        String deniedEmail = "org-create-deny-" + System.nanoTime() + "@example.test";
        var requests = List.of(
                body(post("/api/org/employees"), Map.of("name", "Denied employee", "email", deniedEmail,
                        "phone", "13800000000", "deptPid", departmentPid, "positionPid", position)),
                body(post("/api/org/employees/link"), Map.of("memberPid", member.getPid(), "deptPid", departmentPid,
                        "positionPid", position)),
                body(put("/api/org/employees/{pid}", employeePid), Map.of("org_emp_name", "Denied update")),
                body(put("/api/org/employees/{pid}/transfer", employeePid), Map.of("newDeptPid", "denied-target")),
                body(put("/api/org/employees/batch-transfer"), Map.of("employeePids", List.of(employeePid),
                        "newDeptPid", "denied-target")));
        for (var request : requests) {
            mvc.perform(request).andExpect(status().isForbidden());
            assertThat(employee(employeePid)).isEqualTo(originalEmployee);
            assertThat(jdbc.queryForMap("SELECT * FROM ab_tenant_member WHERE tenant_id = ? AND pid = ?",
                    getTestTenant().getId(), member.getPid())).isEqualTo(originalMember);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mt_org_employee WHERE tenant_id = ?",
                    Long.class, getTestTenant().getId())).isEqualTo(originalCount);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_user WHERE email = ?", Long.class, deniedEmail)).isZero();
        }
    }

    private String position(String departmentPid) {
        return data.create("org_position", Map.of("org_pos_name", "Permission fixture position " + System.nanoTime(),
                "org_pos_dept_id", departmentPid, "org_pos_level", "staff")).get("pid").toString();
    }

    private Map<String, Object> employee(String pid) {
        return jdbc.queryForMap("SELECT * FROM mt_org_employee WHERE tenant_id = ? AND pid = ?",
                getTestTenant().getId(), pid);
    }

    private void assertEmployeeAssignment(String pid, String department, String position) {
        var employee = employee(pid);
        assertThat(employee.get("org_emp_dept_id")).isEqualTo(department);
        assertThat(employee.get("org_emp_position_id")).isEqualTo(position);
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
