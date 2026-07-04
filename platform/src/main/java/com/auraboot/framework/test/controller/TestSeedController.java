package com.auraboot.framework.test.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.test.dto.SeedResult;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.auraboot.framework.common.constant.StatusConstants;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Test seed controller for E2E tests (Playwright + iOS XCUITest).
 * <p>
 * Only active when the "test" Spring profile is enabled.
 * Provides endpoints to create/reset a test tenant with an admin user
 * and return a ready-to-use JWT token.
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@Profile("test")
@RequiredArgsConstructor
public class TestSeedController {

    private static final String TEST_TENANT_NAME = "e2e_test";
    private static final String TEST_USER_EMAIL = "e2e@test.local";
    private static final String TEST_USER_PASSWORD = "E2eTestPass2026!";
    private static final String TEST_USER_DISPLAY_NAME = "E2E Test Admin";

    // Stable second member so user-search (which excludes self) returns at least one result.
    // Required for iOS testCreateOneOnOneChat / testSelectMentionUserInsertsTag and
    // Android ChatSearchAndContactsTest.contactsSearchReturnsResults.
    private static final String TEST_CHAT_USER_EMAIL = "e2e_chat_user@test.local";
    private static final String TEST_CHAT_USER_PASSWORD = "E2eChatUser2026!";
    private static final String TEST_CHAT_USER_DISPLAY_NAME = "E2E Chat User";
    private static final List<String> KNOWN_E2E_FIXTURE_MODELS = List.of(
            "e2et_record",
            "e2et_order",
            "e2et_order_item",
            "e2et_order_log",
            "e2et_customer",
            "e2et_payment",
            "org_department"
    );
    private static final List<String> KNOWN_E2E_RESET_MODELS = List.of(
            "e2et_record",
            "e2et_order",
            "e2et_order_item",
            "e2et_order_log",
            "e2et_customer",
            "e2et_payment",
            "tpm_project",
            "tpm_task",
            "tpm_milestone",
            "showcase_all_fields",
            "org_department",
            "sc_monthly_metric"
    );

    private final TenantService tenantService;
    private final TenantBootstrapService tenantBootstrapService;
    private final TenantMemberService tenantMemberService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final SessionManagementService sessionManagementService;
    private final PluginImportService pluginImportService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final RolePermissionService rolePermissionService;
    private final JdbcTemplate jdbcTemplate;
    private final DynamicDataService dynamicDataService;

    /**
     * POST /api/test/seed
     * <p>
     * Create a test tenant with an admin user if it doesn't exist.
     * If it already exists, return a fresh JWT for the existing user.
     * Accepts optional testRunId query param; generates one if absent.
     */
    @PostMapping("/seed")
    public ResponseEntity<SeedResult> seed(
            @RequestParam(value = "testRunId", required = false) String testRunId) {
        String runId = testRunId != null ? testRunId : generateTestRunId("api");
        log.info("Test seed requested: testRunId={}", runId);

        // 1. Check if test tenant already exists
        Tenant tenant = tenantService.findByName(TEST_TENANT_NAME);
        User user = userService.findByEmail(TEST_USER_EMAIL);

        boolean tenantExists = tenant != null;
        boolean userExists = user != null;

        // 2. Create user if not exists
        if (!userExists) {
            user = userService.signUp(TEST_USER_EMAIL, TEST_USER_PASSWORD, TEST_USER_DISPLAY_NAME);
            log.info("Test user created: userId={}, email={}", user.getId(), TEST_USER_EMAIL);
        }

        // 3. Create tenant if not exists
        if (!tenantExists) {
            tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(TEST_TENANT_NAME);
            tenant.setDisplayName("E2E Test Tenant");
            tenant.setStatus(StatusConstants.ACTIVE);
            tenant.setContactEmail(TEST_USER_EMAIL);
            tenant.setCreatedBy(user.getId());
            tenant.setUpdatedBy(user.getId());
            tenant = tenantService.createTenant(tenant);
            log.info("Test tenant created: tenantId={}", tenant.getId());
        }

        // 4. Create tenant membership
        TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(tenant.getId(), user.getId());
        if (existingMember == null) {
            tenantMemberService.addMember(user.getId(), tenant.getId(), "active");
            log.info("Test user added as tenant member: tenantId={}, userId={}", tenant.getId(), user.getId());
        }

        // 5. Bootstrap only for a newly-created tenant. Existing tenants may already
        // contain seed menus/roles, and re-running bootstrap is not idempotent.
        if (!tenantExists) {
            TenantBootstrapService.BootstrapResult bootstrapResult =
                    tenantBootstrapService.bootstrapTenant(tenant.getId(), user.getId());
            log.info("Tenant bootstrap completed: success={}, roles={}, menus={}, permissions={}, duration={}ms",
                    bootstrapResult.isSuccess(),
                    bootstrapResult.getRolesCreated(),
                    bootstrapResult.getMenusCreated(),
                    bootstrapResult.getPermissionsAssigned(),
                    bootstrapResult.getDurationMs());
        } else {
            log.info("Test tenant already exists, skipping bootstrap and only refreshing plugin state");
        }

        // 5.5 Install test-fixtures plugin into the new test tenant so iOS/Playwright
        // tests can navigate to browse_e2et_order_list without manual intervention.
        ensureModelPermissionsExist(tenant, user);
        installE2eTestPlugin(tenant, user);
        repairDynamicTableIdentitySequences(tenant);

        // 5.6 Ensure a second "colleague" user exists in the test tenant so that
        // mobile user-search (which excludes self) returns at least one result.
        ensureChatColleagueExists(tenant, user);

        // 6. Generate JWT
        String jwt = generateJwt(user, tenant.getId());

        if (tenantExists && userExists) {
            log.info("Test tenant already existed; refreshed bootstrap/plugin state: tenantId={}, userId={}",
                    tenant.getId(), user.getId());
        }

        SeedResult result = SeedResult.builder()
                .tenantId(tenant.getId())
                .userId(user.getId())
                .jwt(jwt)
                .email(TEST_USER_EMAIL)
                .tenantName(TEST_TENANT_NAME)
                .testRunId(runId)
                .build();

        log.info("Test seed completed: tenantId={}, userId={}", tenant.getId(), user.getId());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/test/reset
     * <p>
     * Delete the test tenant and all its data, then re-run seed.
     */
    @PostMapping("/reset")
    public ResponseEntity<SeedResult> reset() {
        log.info("Test reset requested");

        // 0. Dynamic mt_* tables are shared by model code. Tenant deletion does not
        // remove their rows, so old E2E data can block plugin schema re-imports or
        // leave identity sequences behind inserted IDs.
        clearKnownE2eDynamicTables();

        // 1. Find and delete ALL existing test tenants (guard against duplicates
        //    that accumulate from failed partial resets in dev environments).
        int deletedCount = 0;
        Tenant staleTenant;
        while ((staleTenant = tenantService.findByName(TEST_TENANT_NAME)) != null) {
            var members = tenantMemberService.findByTenantId(staleTenant.getId());
            for (TenantMember member : members) {
                tenantMemberService.removeMember(member.getId());
            }
            tenantService.deleteTenant(staleTenant.getId());
            log.info("Test tenant deleted: tenantId={}", staleTenant.getId());
            deletedCount++;
            if (deletedCount > 20) {
                log.warn("Too many test tenants — stopping cleanup after 20 deletions");
                break;
            }
        }
        if (deletedCount > 0) {
            log.info("Deleted {} test tenant(s)", deletedCount);
        }

        // 2. Delete the test user (if exists and not used elsewhere)
        User user = userService.findByEmail(TEST_USER_EMAIL);
        if (user != null) {
            // Check if user belongs to other tenants
            var tenantIds = tenantMemberService.getTenantIdsByUserId(user.getId());
            if (tenantIds.isEmpty()) {
                // Safe to conceptually "remove" — but we just let seed recreate
                log.info("Test user has no other tenants, will be reused by seed");
            }
        }

        // 3. Re-run seed
        return seed(null);
    }

    /**
     * GET /api/test/context
     * <p>
     * Return current test state — useful for debugging and cross-test coordination.
     */
    @GetMapping("/context")
    public ResponseEntity<?> context() {
        Tenant tenant = tenantService.findByName(TEST_TENANT_NAME);
        User user = userService.findByEmail(TEST_USER_EMAIL);

        if (tenant == null || user == null) {
            return ResponseEntity.ok(Map.of(
                    "seeded", false,
                    "message", "Test environment not seeded. Call POST /api/test/seed first."
            ));
        }

        String jwt = generateJwt(user, tenant.getId());

        return ResponseEntity.ok(Map.of(
                "seeded", true,
                "tenantId", tenant.getId(),
                "userId", user.getId(),
                "email", TEST_USER_EMAIL,
                "tenantName", TEST_TENANT_NAME,
                "jwt", jwt
        ));
    }

    /**
     * Install the test-fixtures plugin into the given tenant so that iOS/Playwright
     * E2E tests can navigate to the e2et menu without manual plugin publishing.
     * <p>
     * The plugin directory is resolved relative to the Spring Boot working directory
     * ({@code user.dir}), which is the {@code platform/} module root at runtime.
     * The plugins directory sits one level above: {@code ../plugins/test-fixtures}.
     * <p>
     * Failure is non-fatal — logs a warning and continues so that seed never breaks
     * due to a missing plugin directory (e.g. CI environments without the full tree).
     */
    private void installE2eTestPlugin(Tenant tenant, User user) {
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getEmail());
        try {
            try {
                importTestPlugin("../plugins/project-management", "project-management", tenant.getId());
                importFirstAvailableTestPlugin(
                        tenant.getId(),
                        "org-management",
                        "../plugins/org-management",
                        "../../auraboot/plugins/org-management"
                );
                importFirstAvailableTestPlugin(
                        tenant.getId(),
                        "showcase",
                        "../plugins/showcase",
                        "../../auraboot/plugins/showcase"
                );
                // OSS test-fixtures first: under Enterprise host bootRun, "../plugins"
                // resolves to the Enterprise test-fixtures whose pages currently fail
                // page validation; the OSS copy is the validation-clean canonical one
                // (also what the docker mobile-e2e stack imports). Docker is unaffected:
                // the "../../auraboot/..." path does not exist there, so it falls back to
                // "../plugins/test-fixtures" (= the OSS mount).
                importFirstAvailableTestPlugin(
                        tenant.getId(),
                        "test-fixtures",
                        "../../auraboot/plugins/test-fixtures",
                        "../plugins/test-fixtures"
                );
                // Mobile E2E (Android/iOS) targets crm_account as the canonical "real" model
                // (see EndpointRegistryTest in apps/android and EndpointRegistryTests.swift in
                // apps/ios). The CRM plugin lives in the enterprise overlay; importTestPlugin
                // safely skips when the directory is absent (OSS-only checkouts).
                importTestPlugin("../plugins/crm", "crm", tenant.getId());
            } catch (Exception e) {
                log.warn("test-fixtures plugin install threw exception for tenant {}: {}",
                        tenant.getId(), e.getMessage());
            }

            ensureTestAdminCanUseImportedResources(tenant, user);
            ensureShowcasePagesImportedForMobileE2e(tenant);
            ensureOrgDepartmentSeedDataForMobileE2e(tenant, user);
            ensureShowcaseAllFieldsSeedDataForMobileE2e(tenant, user);
            seedCrmDemoRecords(tenant, user);
        } finally {
            MetaContext.clear();
        }
    }

    private void ensureShowcasePagesImportedForMobileE2e(Tenant tenant) {
        Integer pageCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ab_page_schema
                WHERE tenant_id = ?
                  AND page_key IN (
                    'showcase_all_fields_list',
                    'showcase_all_fields_form',
                    'showcase_all_fields_detail'
                )
                  AND model_code = 'showcase_all_fields'
                  AND status = 'published'
                  AND deleted_flag = FALSE
                """, Integer.class, tenant.getId());
        if (pageCount == null || pageCount < 3) {
            throw new IllegalStateException(
                    "Mobile showcase seed requires published PageSchemas for list/form/detail; " +
                            "found=" + pageCount + ", tenant=" + tenant.getId());
        }
    }

    private void ensureOrgDepartmentSeedDataForMobileE2e(Tenant tenant, User user) {
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                "public.mt_org_department"
        );
        if (!Boolean.TRUE.equals(tableExists)) {
            throw new IllegalStateException(
                    "Mobile showcase seed requires org_department table for organizationselect; tenant="
                            + tenant.getId());
        }

        jdbcTemplate.update("""
                DELETE FROM mt_org_department
                WHERE pid = 'mob_showcase_dept_sales_01'
                   OR (
                    tenant_id = ?
                    AND org_dept_code = 'DEPT-20260516-001'
                   )
                """, tenant.getId());
        jdbcTemplate.update("""
                INSERT INTO mt_org_department (
                    pid, created_by, updated_by, tenant_id, created_at, updated_at,
                    org_dept_code, org_dept_name, org_dept_parent_id,
                    org_dept_manager_id, org_dept_order, org_dept_status
                )
                VALUES (
                    'mob_showcase_dept_sales_01', ?, ?, ?, NOW(), NOW(),
                    'DEPT-20260516-001', '销售部', NULL,
                    NULL, 10, 'active'
                )
                """,
                user.getId(),
                user.getId(),
                tenant.getId());
        log.info("Inserted mobile showcase organization seed rows: tenantId={}, rows=1", tenant.getId());
    }

    private void ensureShowcaseAllFieldsSeedDataForMobileE2e(Tenant tenant, User user) {
            Integer existing = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM mt_showcase_all_fields
                    WHERE tenant_id = ?
                    """, Integer.class, tenant.getId());
            if (existing != null && existing > 0) {
                log.info("Refreshing canonical mobile showcase seed rows: tenantId={}, existingRows={}",
                        tenant.getId(), existing);
            }
            jdbcTemplate.update("""
                    DELETE FROM mt_showcase_all_fields
                    WHERE pid IN (
                        'mobile_showcase_iot_gateway',
                        'mobile_showcase_flexible_pc',
                        'mobile_showcase_sensor_module',
                        'mobile_showcase_power_unit'
                    )
                    OR (
                        tenant_id = ?
                        AND sc_code IN (
                            'SC-20260513-005',
                            'SC-20260513-006',
                            'SC-20260513-004',
                            'SC-20260513-007'
                        )
                    )
                    """, tenant.getId());

            insertShowcaseAllFieldsRecord(
                    tenant,
                    user,
                    "mobile_showcase_iot_gateway",
                    "物联网网关开发套件",
                    "SC-20260513-005",
                    "含 Linux 主板 + 4G 模块 + WiFi + BLE + GPS，预装 AuraBoot Agent",
                    24,
                    "1280.00",
                    true,
                "2026-05-13",
                "2026-09-30",
                "2026-05-13T12:06:18+00:00",
                "active",
                "high",
                "electronics",
                "iot,gateway,edge",
                82,
                5,
                    "#2563EB",
                    "https://auraboot.io/showcase/iot-gateway",
                    "iot@auraboot.io",
                    "010-82568900",
                    "<h2>物联网网关开发套件 v3.2</h2><p>一站式 <strong>IoT 网关解决方案</strong>，预装 <em>AuraBoot Agent</em>，开箱即用，支持多协议接入。</p><h3>硬件配置</h3><ul><li>处理器：<strong>ARM Cortex-A7</strong> 双核 1GHz</li><li>通信：<em>4G LTE / WiFi / BLE 5.0 / GPS</em></li></ul>",
                    """
                    [{"url":"/files/iot-quickstart.pdf","name":"IoT 网关快速入门指南.pdf","size":1572864,"type":"application/pdf"},{"url":"/files/firmware-v3.2.1.bin","name":"固件 v3.2.1.bin","size":8388608,"type":"application/octet-stream"}]
                    """,
                    "已发出 50 套样品，反馈良好，计划量产",
                    "125000.00",
                    "09:30",
                    "2026-05-13~2026-09-30",
                    "09:00-18:00",
                    "移动端记录渲染验证的工业网关套件",
                    "北京市海淀区中关村软件园 A 座 8 层",
                    "已启用远程管理与 OTA 升级"
            );
            insertShowcaseAllFieldsRecord(
                    tenant,
                    user,
                    "mobile_showcase_flexible_pc",
                    "柔性PC控制板",
                    "SC-20260513-006",
                    "控制板开发与验证",
                12,
                "860.00",
                true,
                "2026-05-14",
                "2026-07-15",
                "2026-05-14T09:15:00+00:00",
                "active",
                "low",
                "electronics",
                "pc,controller",
                48,
                4,
                    "#16A34A",
                    "https://auraboot.io/showcase/controller",
                    "controller@auraboot.io",
                    "010-82568901",
                    "<p>柔性 PC 控制板验证套件，覆盖控制逻辑与边缘接入。</p>",
                    """
                    [{"url":"/files/controller-spec.pdf","name":"控制板规格说明.pdf","size":1048576,"type":"application/pdf"}]
                    """,
                    "验证中",
                    "56000.00",
                    "10:00",
                    "2026-05-14~2026-07-15",
                    "10:00-17:00",
                    "列表卡片对比用控制板记录",
                    "二号实验室",
                    ""
            );
            insertShowcaseAllFieldsRecord(
                    tenant,
                    user,
                    "mobile_showcase_sensor_module",
                    "工业级传感器模块",
                    "SC-20260513-004",
                    "高精度工业传感器，支持多协议",
                36,
                "420.00",
                true,
                "2026-05-12",
                "2026-08-31",
                "2026-05-12T16:20:00+00:00",
                "review",
                "medium",
                "electronics",
                "sensor,industrial",
                64,
                4,
                    "#7C3AED",
                    "https://auraboot.io/showcase/sensor",
                    "sensor@auraboot.io",
                    "010-82568902",
                    "<p>待审核的工业传感器模块，覆盖状态与优先级渲染。</p>",
                    """
                    [{"url":"/files/sensor-guide.pdf","name":"传感器接入指南.pdf","size":734003,"type":"application/pdf"}]
                    """,
                    "需要审核",
                    "78000.00",
                    "14:30",
                    "2026-05-12~2026-08-31",
                    "08:30-17:30",
                    "状态与优先级渲染验证用传感器模块",
                    "C 区工厂",
                    ""
            );
            insertShowcaseAllFieldsRecord(
                    tenant,
                    user,
                    "mobile_showcase_power_unit",
                    "智能电源管理单元",
                    "SC-20260513-007",
                    "电源管理与能效优化方案",
                18,
                "620.00",
                false,
                "2026-05-15",
                "2026-10-01",
                "2026-05-15T08:00:00+00:00",
                "draft",
                "medium",
                "electronics",
                "power,energy",
                20,
                3,
                    "#F59E0B",
                    "https://auraboot.io/showcase/power",
                    "power@auraboot.io",
                    "010-82568903",
                    "<p>草稿状态的智能电源管理方案。</p>",
                    "[]",
                    "草稿记录",
                    "43000.00",
                    "15:00",
                    "2026-05-15~2026-10-01",
                    "09:00-16:00",
                    "无效布尔状态渲染验证用电源单元",
                    "一号仓库",
                    ""
            );
        log.info("Inserted mobile showcase seed rows: tenantId={}, rows=4", tenant.getId());
    }

    private void insertShowcaseAllFieldsRecord(
            Tenant tenant,
            User user,
            String pid,
            String name,
            String code,
            String description,
            int quantity,
            String price,
            boolean active,
            String startDate,
            String endDate,
            String createdAt,
            String status,
            String priority,
            String category,
            String tags,
            int progress,
            int rating,
            String color,
            String website,
            String email,
            String phone,
            String richText,
            String attachmentJson,
            String remark,
            String budget,
            String timeSlot,
            String dateRange,
            String workingHours,
            String aiSummary,
            String address,
            String advancedSettings) {
        jdbcTemplate.update("""
                INSERT INTO mt_showcase_all_fields (
                    pid, created_by, updated_by, tenant_id, created_at, updated_at,
                    sc_name, sc_code, sc_description, sc_quantity, sc_price,
                    sc_is_active, sc_start_date, sc_end_date, sc_created_at,
                    sc_status, sc_priority, sc_category, sc_tags, sc_progress,
                    sc_rating, sc_color, sc_website, sc_email, sc_phone,
                    sc_richtext_content, sc_attachment, sc_remark, sc_budget,
                    sc_time_slot, sc_date_range, sc_working_hours,
                    sc_cascade_category, sc_tree_node, sc_assignee, sc_team_members,
                    sc_department, sc_address, sc_ai_summary, sc_owner_user,
                    sc_attachment_file, sc_advanced_settings
                )
                VALUES (
                    ?, ?, ?, ?, NOW(), NOW(),
                    ?, ?, ?, ?, ?::numeric,
                    ?, ?::date, ?::date, ?::timestamptz,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?::jsonb, ?, ?::numeric,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?
                )
                """,
                pid, user.getId(), user.getId(), tenant.getId(),
                name, code, description, quantity, price,
                active, startDate, endDate, createdAt,
                status, priority, category, tags, progress,
                rating, color, website, email, phone,
                richText, attachmentJson, remark, budget,
                timeSlot, dateRange, workingHours,
                "electronics/gateway/edge", "engineering", user.getPid(), user.getPid(),
                "platform", address, aiSummary, user.getPid(),
                attachmentJson, advancedSettings);
    }

    /**
     * Seed a small set of demo {@code crm_account} records for mobile E2E smoke tests.
     * <p>
     * Mobile EndpointRegistry tests assert that {@code /api/dynamic/crm_account/list}
     * and {@code /api/dynamic/crm_account/{id}} return at least one record. The CRM
     * plugin import only registers the model definition; without explicit seeding the
     * table is empty in the freshly-bootstrapped test tenant.
     * <p>
     * Idempotent: skips when the model is missing (CRM plugin not present in OSS-only
     * checkouts) or when records already exist for the tenant. Goes through
     * {@link DynamicDataService#create} so tenant context, soft-delete, audit and
     * primary-key generation match production paths (no manual SQL INSERTs).
     */
    private void seedCrmDemoRecords(Tenant tenant, User user) {
        String modelCode = "crm_account";

        Integer modelExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ab_meta_model
                WHERE tenant_id = ?
                  AND code = ?
                  AND deleted_flag = FALSE
                """, Integer.class, tenant.getId(), modelCode);
        if (modelExists == null || modelExists == 0) {
            log.info("Skipping crm_account demo seed; model not imported for tenant {}", tenant.getId());
            return;
        }

        Integer existingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mt_crm_account WHERE tenant_id = ?",
                Integer.class, tenant.getId());
        if (existingRows != null && existingRows >= 3) {
            log.info("Skipping crm_account demo seed; tenant {} already has {} record(s)",
                    tenant.getId(), existingRows);
            return;
        }

        // Minimum payload covers both NOT NULL columns (crm_acc_code, crm_acc_name).
        // Optional fields populated for realistic list/detail rendering in smoke tests.
        List<Map<String, Object>> demoRecords = List.of(
                buildCrmAccountPayload("E2E-ACC-001", "E2E Demo Account Alpha",
                        "technology", "active", "A"),
                buildCrmAccountPayload("E2E-ACC-002", "E2E Demo Account Beta",
                        "manufacturing", "active", "B"),
                buildCrmAccountPayload("E2E-ACC-003", "E2E Demo Account Gamma",
                        "automotive", "active", "C")
        );

        int created = 0;
        for (Map<String, Object> payload : demoRecords) {
            Map<String, Object> result = dynamicDataService.create(modelCode, payload);
            if (result != null) {
                created++;
            }
        }
        log.info("Seeded {} crm_account demo record(s) for E2E tenant {}", created, tenant.getId());
    }

    private Map<String, Object> buildCrmAccountPayload(String code, String name,
            String industry, String status, String rating) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("crm_acc_code", code);
        payload.put("crm_acc_name", name);
        payload.put("crm_acc_industry", industry);
        payload.put("crm_acc_status", status);
        payload.put("crm_acc_rating", rating);
        return payload;
    }

    private void ensureTestAdminCanUseImportedResources(Tenant tenant, User user) {
        ensureModelPermissionsExist(tenant, user);

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenant.getId(), user.getId());
        if (member == null) {
            log.warn("Cannot repair test admin permissions: tenant member missing for tenant={}, user={}",
                    tenant.getId(), user.getId());
            return;
        }

        List<Long> tenantAdminRoleIds = jdbcTemplate.queryForList("""
                SELECT id
                FROM ab_role
                WHERE tenant_id = ?
                  AND code = 'tenant_admin'
                ORDER BY created_at DESC
                LIMIT 1
                """, Long.class, tenant.getId());
        if (tenantAdminRoleIds.isEmpty()) {
            log.warn("Cannot repair test admin permissions: tenant_admin role missing for tenant={}",
                    tenant.getId());
            return;
        }
        Long tenantAdminRoleId = tenantAdminRoleIds.get(0);

        var existingRole = userRoleService.findByMemberIdAndRoleIdAndTenantId(
                member.getId(), tenantAdminRoleId, tenant.getId());
        if (existingRole == null) {
            roleService.assignRoleToMember(member.getId(), tenantAdminRoleId, tenant.getId());
            log.info("Assigned tenant_admin to E2E seed member: tenantId={}, memberId={}",
                    tenant.getId(), member.getId());
        }

        List<Long> permissionIds = jdbcTemplate.queryForList("""
                SELECT id
                FROM ab_permission
                WHERE tenant_id = ?
                  AND status = 'active'
                  AND deleted_flag = FALSE
                """, Long.class, tenant.getId());
        if (!permissionIds.isEmpty()) {
            rolePermissionService.assignPermissionsToRole(tenantAdminRoleId, permissionIds);
            log.info("Ensured tenant_admin has {} active permissions for E2E tenant {}",
                    permissionIds.size(), tenant.getId());
        } else {
            log.warn("No active permissions found while repairing E2E tenant admin access: tenant={}",
                    tenant.getId());
        }
    }

    private void ensureModelPermissionsExist(Tenant tenant, User user) {
        List<String> modelCodes = findE2eModelCodes(tenant.getId());

        int inserted = 0;
        for (String modelCode : modelCodes) {
            for (String action : List.of("read", "create", "update", "delete", "export", "import")) {
                String permissionCode = "model." + modelCode + "." + action;
                Integer existing = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM ab_permission
                        WHERE tenant_id = ?
                          AND code = ?
                        """, Integer.class, tenant.getId(), permissionCode);
                if (existing != null && existing > 0) {
                    jdbcTemplate.update("""
                            UPDATE ab_permission
                            SET status = 'active',
                                deleted_flag = FALSE,
                                updated_by = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE tenant_id = ?
                              AND code = ?
                            """, user.getId(), tenant.getId(), permissionCode);
                    continue;
                }

                inserted += jdbcTemplate.update("""
                        INSERT INTO ab_permission (
                            pid, tenant_id, code, name, resource_type, resource_code, action,
                            source, source_ref, status, deleted_flag, created_by, updated_by
                        )
                        VALUES (?, ?, ?, ?, 'model', ?, ?, 'test-seed', ?, 'active', FALSE, ?, ?)
                        """,
                        UniqueIdGenerator.generate(),
                        tenant.getId(),
                        permissionCode,
                        "Model " + modelCode + " " + action,
                        modelCode,
                        action,
                        modelCode,
                        user.getId(),
                        user.getId()
                );
            }
        }

        if (inserted > 0) {
            log.info("Created {} missing model permissions for E2E tenant {}", inserted, tenant.getId());
        }
    }

    private void repairDynamicTableIdentitySequences(Tenant tenant) {
        int repaired = 0;
        for (String modelCode : findE2eModelCodes(tenant.getId())) {
            String tableName = SystemFieldConstants.generateTableName(modelCode);
            if (!isSafeSqlIdentifier(tableName)) {
                throw new IllegalStateException("Unsafe dynamic table name: " + tableName);
            }

            Boolean tableExists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + tableName
            );
            if (!Boolean.TRUE.equals(tableExists)) {
                log.debug("Skipping E2E identity sequence repair because table is missing: tenant={}, model={}, table={}",
                        tenant.getId(), modelCode, tableName);
                continue;
            }

            String sequenceName = jdbcTemplate.queryForObject(
                    "SELECT pg_get_serial_sequence(?, 'id')",
                    String.class,
                    "public." + tableName
            );
            if (sequenceName == null || sequenceName.isBlank()) {
                log.debug("Skipping E2E identity sequence repair because id sequence is missing: tenant={}, model={}, table={}",
                        tenant.getId(), modelCode, tableName);
                continue;
            }

            Long nextId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) + 1 FROM " + quoteIdentifier(tableName),
                    Long.class
            );
            jdbcTemplate.queryForObject(
                    "SELECT setval(to_regclass(?), ?, false)",
                    Long.class,
                    sequenceName,
                    nextId
            );
            repaired++;
            log.info("Repaired E2E dynamic table identity sequence: tenant={}, model={}, table={}, sequence={}, nextId={}",
                    tenant.getId(), modelCode, tableName, sequenceName, nextId);
        }

        if (repaired > 0) {
            log.info("Repaired {} E2E dynamic table identity sequence(s) for tenant {}",
                    repaired, tenant.getId());
        }
    }

    private List<String> findE2eModelCodes(Long tenantId) {
        List<String> importedModelCodes = jdbcTemplate.queryForList("""
                SELECT DISTINCT code
                FROM ab_meta_model
                WHERE tenant_id = ?
                  AND deleted_flag = FALSE
                ORDER BY code
                """, String.class, tenantId);
        LinkedHashSet<String> modelCodes = new LinkedHashSet<>(importedModelCodes);
        modelCodes.addAll(KNOWN_E2E_FIXTURE_MODELS);
        if (importedModelCodes.isEmpty()) {
            log.warn("No imported models found while repairing E2E resources; using known fixture models: tenant={}",
                    tenantId);
        }
        return List.copyOf(modelCodes);
    }

    private void clearKnownE2eDynamicTables() {
        int truncated = 0;
        for (String modelCode : KNOWN_E2E_RESET_MODELS) {
            String tableName = SystemFieldConstants.generateTableName(modelCode);
            if (!isSafeSqlIdentifier(tableName)) {
                throw new IllegalStateException("Unsafe dynamic table name: " + tableName);
            }

            Boolean tableExists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + tableName
            );
            if (!Boolean.TRUE.equals(tableExists)) {
                continue;
            }

            jdbcTemplate.execute("TRUNCATE TABLE " + quoteIdentifier(tableName) + " RESTART IDENTITY CASCADE");
            truncated++;
            log.info("Cleared E2E dynamic table before reset: model={}, table={}", modelCode, tableName);
        }
        if (truncated > 0) {
            log.info("Cleared {} E2E dynamic table(s) before reseeding", truncated);
        }
    }

    private boolean isSafeSqlIdentifier(String identifier) {
        return identifier != null && identifier.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private String quoteIdentifier(String identifier) {
        if (!isSafeSqlIdentifier(identifier)) {
            throw new IllegalStateException("Unsafe SQL identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    private void importTestPlugin(String relativePath, String pluginName, Long tenantId) {
        Path pluginDir = Path.of(System.getProperty("user.dir"))
                .resolve(relativePath)
                .normalize();

        if (!pluginDir.toFile().isDirectory()) {
            log.warn("{} plugin directory not found at {}, skipping plugin install", pluginName, pluginDir);
            return;
        }

        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginDir.toString());
        if (!preview.isValid()) {
            log.warn("{} plugin parse failed: {}", pluginName, preview.getErrors());
            return;
        }

        ImportRequest importRequest = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishCommands(true)
                .autoPublishPages(true)
                .autoDeployProcesses(true)
                .build();
        var result = pluginImportService.execute(preview.getImportId(), importRequest);
        if (result.isSuccess()) {
            log.info("{} plugin installed for tenant {}: counts={}",
                    pluginName, tenantId, result.getResourceCounts());
        } else {
            log.warn("{} plugin install failed for tenant {}: {}",
                    pluginName, tenantId, result.getErrorMessage());
        }
    }

    private void importFirstAvailableTestPlugin(Long tenantId, String pluginName, String... relativePaths) {
        Path workingDir = Path.of(System.getProperty("user.dir"));
        for (String relativePath : relativePaths) {
            Path pluginDir = workingDir.resolve(relativePath).normalize();
            if (pluginDir.toFile().isDirectory()) {
                importTestPlugin(relativePath, pluginName, tenantId);
                return;
            }
        }
        log.warn("{} plugin directory not found in any candidate path {}, skipping plugin install",
                pluginName, List.of(relativePaths));
    }

    /**
     * GET /api/test/run-id?platform=web
     * <p>
     * Generate a testRunId conforming to the Test Session Contract format:
     * {platform}_{unixSeconds}_{4-hex-random}
     */
    @GetMapping("/run-id")
    public ResponseEntity<Map<String, String>> generateRunIdEndpoint(
            @RequestParam(value = "platform", defaultValue = "api") String platform) {
        return ResponseEntity.ok(Map.of("testRunId", generateTestRunId(platform)));
    }

    /**
     * Generate a testRunId per the Test Session Contract (section 5):
     * {platform}_{unixSeconds}_{4-hex-random}
     */
    static String generateTestRunId(String platform) {
        long ts = Instant.now().getEpochSecond();
        String hex = String.format("%04x", new SecureRandom().nextInt(0xFFFF));
        return platform + "_" + ts + "_" + hex;
    }

    /**
     * Ensure a stable "colleague" user exists in the test tenant so that mobile
     * user-search (which excludes self) returns at least one result for the primary
     * test user. Required for iOS testCreateOneOnOneChat /
     * testSelectMentionUserInsertsTag and Android contactsSearchReturnsResults.
     * <p>
     * This is idempotent: if the colleague already exists and is already a member
     * of the tenant, it returns immediately without making any writes.
     */
    private void ensureChatColleagueExists(Tenant tenant, User primaryUser) {
        try {
            User colleague = userService.findByEmail(TEST_CHAT_USER_EMAIL);
            if (colleague == null) {
                colleague = userService.signUp(
                        TEST_CHAT_USER_EMAIL, TEST_CHAT_USER_PASSWORD, TEST_CHAT_USER_DISPLAY_NAME);
                log.info("E2E chat colleague created: userId={}, email={}", colleague.getId(), TEST_CHAT_USER_EMAIL);
            }
            TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(
                    tenant.getId(), colleague.getId());
            if (existingMember == null) {
                tenantMemberService.addMember(colleague.getId(), tenant.getId(), "active");
                log.info("E2E chat colleague added to tenant: tenantId={}, userId={}",
                        tenant.getId(), colleague.getId());
            }
        } catch (Exception e) {
            // Non-fatal: log a warning. The primary flow must not be broken by
            // a colleague-creation failure.
            log.warn("Failed to ensure E2E chat colleague ({}): {}", TEST_CHAT_USER_EMAIL, e.getMessage());
        }
    }

    /**
     * Generate a JWT token for the given user and tenant.
     */
    private String generateJwt(User user, Long tenantId) {
        TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        if (tenantMember == null) {
            throw new IllegalStateException("Test seed user is not a member of tenant " + tenantId);
        }

        CustomUserDetails userDetails = new CustomUserDetails(
                user.getEmail(),
                user.getPassword() != null ? user.getPassword() : "",
                user.getId(),
                user.getPid(),
                Collections.singletonList(new SimpleGrantedAuthority("role_user")),
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled()
        );

        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String jwt = jwtUtil.generateTokenWithTenantId(
                userDetails,
                user.getPid(),
                tenantId,
                tenantMember.getId(),
                securityVersion
        );

        // Register server-side session so the JWT passes session validation
        try {
            sessionManagementService.createSession(user.getId(), jwt, "test-seed", "TestSeedController");
        } catch (Exception e) {
            log.warn("Failed to create session for test user: {}", e.getMessage());
        }

        return jwt;
    }
}
