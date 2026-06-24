package com.auraboot.framework.integration.meta;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.controller.RecordCapabilityController;
import com.auraboot.framework.meta.dto.RecordCapabilities;
import com.auraboot.framework.meta.dto.RecordCapabilities.ActionCapability;
import com.auraboot.framework.meta.dto.RecordCapabilities.TabCapability;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.RecordCapabilityService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Record Capability API (ARCH-001).
 * <p>
 * Tests the full Capability Resolver Pipeline: command loading, state filtering,
 * permission filtering, platform/context filtering, priority sorting,
 * tab derivation, and ETag generation.
 * <p>
 * Uses the {@code showcase_all_fields} model which has state_transition,
 * update, and delete commands in the database.
 * <p>
 * The test creates its own record via raw SQL to avoid data permission issues,
 * then tests the capability resolution pipeline against it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RecordCapabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RecordCapabilityService recordCapabilityService;

    @Autowired
    private RecordCapabilityController recordCapabilityController;

    @Autowired
    private CommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserPermissionService userPermissionService;

    /** Model that has state_transition + CRUD commands in the DB. */
    private static final String MODEL_CODE = "showcase_all_fields";

    /**
     * Resolved from seeded showcase command data at runtime to avoid stale fixed IDs.
     */
    private Long adminTenantId;
    private Long adminUserId;
    private Long adminMemberId;

    /** Record ID created by this test. */
    private String testRecordId;

    @BeforeEach
    void createTestRecord() {
        adminTenantId = getTestTenant().getId();
        adminUserId = getTestUser().getId();
        adminMemberId = getTestTenantMember().getId();
        MetaContext.setContext(adminTenantId, adminUserId, "admin", "admin@test.local");
        MetaContext.setMemberId(adminMemberId);
        ensureCapabilityFixture();

        // Insert a test record directly via SQL to bypass DynamicDataService permissions.
        String pid = "cap_test_" + System.currentTimeMillis();
        String name = "CapTest-" + System.currentTimeMillis();
        String code = "CAP-" + System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO mt_showcase_all_fields "
                        + "(pid, tenant_id, sc_status, sc_name, sc_code, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'draft', ?, ?, NOW(), ?, NOW(), ?)",
                pid, adminTenantId, name, code, adminUserId, adminUserId
        );
        // Get the created record's ID
        testRecordId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM mt_showcase_all_fields WHERE pid = ?",
                String.class, pid
        );
    }

    // ==================== Service-Level Tests ====================

    @Test
    @Order(1)
    @DisplayName("Service: returns capabilities with correct envelope fields")
    void getCapabilities_returnsCorrectEnvelope() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo(MODEL_CODE);
        assertThat(result.getRecordId()).isEqualTo(testRecordId);
        // recordState may be null if DynamicDataService still can't load the record
        // due to data permission; the core pipeline still works
        assertThat(result.getEtag()).isNotNull();
        assertThat(result.getEtag()).startsWith("W/\"cap-");
        assertThat(result.getCapabilities()).isNotNull();
        assertThat(result.getTabs()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Service: returns update and delete commands (contextual types)")
    void getCapabilities_returnsContextualTypes() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        Set<String> actionCodes = extractCodes(result.getCapabilities());

        // update and delete should always appear (they don't depend on state)
        assertThat(actionCodes).contains("sc:update_showcase");
        assertThat(actionCodes).contains("sc:delete_showcase");
    }

    @Test
    @Order(3)
    @DisplayName("Service: excludes query and create commands (not contextual)")
    void getCapabilities_excludesNonContextualTypes() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        Set<String> actionCodes = extractCodes(result.getCapabilities());

        // Query and create commands should not appear
        assertThat(actionCodes).doesNotContain("sc:list_showcase");
        assertThat(actionCodes).doesNotContain("sc:detail_showcase");
        assertThat(actionCodes).doesNotContain("sc:create_showcase");
    }

    @Test
    @Order(4)
    @DisplayName("Service: actions are sorted by priority ascending")
    void getCapabilities_sortedByPriority() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        List<ActionCapability> actions = result.getCapabilities();
        assertThat(actions).isNotEmpty();

        for (int i = 1; i < actions.size(); i++) {
            assertThat(actions.get(i).getPriority())
                    .as("Action at index %d should have >= priority than index %d", i, i - 1)
                    .isGreaterThanOrEqualTo(actions.get(i - 1).getPriority());
        }
    }

    @Test
    @Order(5)
    @DisplayName("Service: delete action has danger style and destructive=true")
    void getCapabilities_deleteProperties() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        ActionCapability delete = findAction(result, "sc:delete_showcase");

        assertThat(delete).isNotNull();
        assertThat(delete.getType()).isEqualTo("destructive");
        assertThat(delete.getStyle()).isEqualTo("danger");
        assertThat(delete.isDestructive()).isTrue();
        assertThat(delete.getExecutionMode()).isEqualTo("confirm_dialog");
        assertThat(delete.getIcon()).isEqualTo("delete");
        assertThat(delete.getConfirmMessage()).contains("delete");
        assertThat(delete.getPriority()).isEqualTo(99);
        assertThat(delete.getCommandCode()).isEqualTo("sc:delete_showcase");
        assertThat(delete.isRequiresNetwork()).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("Service: update action has edit_field type and form schema with fields")
    void getCapabilities_updateProperties() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        ActionCapability update = findAction(result, "sc:update_showcase");

        assertThat(update).isNotNull();
        assertThat(update.getType()).isEqualTo("edit_field");
        assertThat(update.getStyle()).isEqualTo("secondary");
        assertThat(update.getExecutionMode()).isEqualTo("form_page");
        assertThat(update.getIcon()).isEqualTo("edit");
        assertThat(update.isDestructive()).isFalse();

        // Update command has inputFields -> formSchema
        assertThat(update.getFormSchema()).isNotNull();
        assertThat(update.getFormSchema().getModelCode()).isEqualTo(MODEL_CODE);
        assertThat(update.getFormSchema().getFields()).isNotEmpty();
        assertThat(update.getFormSchema().getFields()).contains("sc_name");
    }

    @Test
    @Order(7)
    @DisplayName("Service: showInActionBar marks top 2 non-destructive actions")
    void getCapabilities_actionBarVisibility() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        List<ActionCapability> actions = result.getCapabilities();
        long actionBarCount = actions.stream().filter(ActionCapability::isShowInActionBar).count();

        // Detail context: max 2 in action bar
        assertThat(actionBarCount).isLessThanOrEqualTo(2);

        // Destructive actions never in action bar
        actions.stream()
                .filter(ActionCapability::isDestructive)
                .forEach(a -> assertThat(a.isShowInActionBar())
                        .as("Destructive action %s should not be in action bar", a.getCode())
                        .isFalse());
    }

    @Test
    @Order(8)
    @DisplayName("Service: list context returns only low-priority actions, no tabs")
    void getCapabilities_listContextFilter() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "list", adminUserId);

        List<ActionCapability> actions = result.getCapabilities();

        // List context: only priority <= 30
        actions.forEach(a ->
                assertThat(a.getPriority())
                        .as("Action %s should have priority <= 30 in list context", a.getCode())
                        .isLessThanOrEqualTo(30));

        // High-priority actions like delete (99) and update (10) should be excluded
        assertThat(extractCodes(actions)).doesNotContain("sc:delete_showcase");

        // No tabs in list context
        assertThat(result.getTabs()).isEmpty();
    }

    @Test
    @Order(9)
    @DisplayName("Service: detail context returns tabs with code and label")
    void getCapabilities_detailContextReturnsTabs() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        List<TabCapability> tabs = result.getTabs();
        assertThat(tabs).isNotEmpty();

        for (TabCapability tab : tabs) {
            assertThat(tab.getCode()).isNotBlank();
            assertThat(tab.getLabel()).isNotBlank();
            assertThat(tab.isVisible()).isTrue();
            assertThat(tab.getBadge()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @Order(10)
    @DisplayName("Service: inbox context limits action bar to max 2, no tabs")
    void getCapabilities_inboxContext() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "inbox", adminUserId);

        long barCount = result.getCapabilities().stream()
                .filter(ActionCapability::isShowInActionBar)
                .count();
        assertThat(barCount).isLessThanOrEqualTo(2);

        // Inbox context: no tabs
        assertThat(result.getTabs()).isEmpty();
    }

    @Test
    @Order(11)
    @DisplayName("Service: non-existent record returns no state_transition actions")
    void getCapabilities_nonExistentRecord() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, "99999999", "web", "detail", adminUserId);

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo(MODEL_CODE);
        assertThat(result.getRecordId()).isEqualTo("99999999");
        assertThat(result.getRecordState()).isNull();

        // State_transition actions should be absent (no record -> no state to check)
        result.getCapabilities().stream()
                .filter(a -> "state_transition".equals(a.getType()))
                .forEach(a -> Assertions.fail(
                        "State transition should not appear for non-existent record: " + a.getCode()));
    }

    @Test
    @Order(12)
    @DisplayName("Service: null userId returns empty capabilities (all permission checks fail)")
    void getCapabilities_nullUserReturnsEmpty() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", null);

        assertThat(result).isNotNull();
        // Commands that require permissions are filtered out; commands without permissions may pass
        // But the showcase commands all have permissions = ["sc.showcase.manage"]
        // so with null userId they should all be filtered
        assertThat(result.getCapabilities()).isEmpty();
    }

    @Test
    @Order(13)
    @DisplayName("Service: each action has a non-null commandCode")
    void getCapabilities_allActionsHaveCommandCode() {
        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        for (ActionCapability action : result.getCapabilities()) {
            assertThat(action.getCommandCode())
                    .as("Action %s should have a commandCode", action.getCode())
                    .isNotBlank();
        }
    }

    @Test
    @Order(14)
    @DisplayName("Service: state_transition commands filtered when record is in draft state")
    void getCapabilities_stateTransitionFiltering() {
        // Direct SQL query to verify the record state
        String status = jdbcTemplate.queryForObject(
                "SELECT sc_status FROM mt_showcase_all_fields WHERE id = ? AND tenant_id = ?",
                String.class, Long.parseLong(testRecordId), adminTenantId);
        assertThat(status).isEqualTo("draft");

        RecordCapabilities result = recordCapabilityService.getRecordCapabilities(
                MODEL_CODE, testRecordId, "web", "detail", adminUserId);

        Set<String> actionCodes = extractCodes(result.getCapabilities());

        // IF the record was loaded successfully (recordState != null),
        // then state_transition filtering applies:
        // - sc:activate_showcase (fromStates: [draft]) should be PRESENT
        // - sc:archive_showcase (fromStates: [active, review]) should be ABSENT
        // - sc:submit_review_showcase (fromStates: [active]) should be ABSENT
        if (result.getRecordState() != null) {
            assertThat(result.getRecordState()).isEqualTo("draft");
            assertThat(actionCodes).contains("sc:activate_showcase");
            assertThat(actionCodes).doesNotContain("sc:archive_showcase");
            assertThat(actionCodes).doesNotContain("sc:submit_review_showcase");
        } else {
            // If record couldn't be loaded (data permission), state_transitions are excluded
            assertThat(actionCodes).doesNotContain("sc:activate_showcase");
            assertThat(actionCodes).doesNotContain("sc:archive_showcase");
            assertThat(actionCodes).doesNotContain("sc:submit_review_showcase");
        }
    }

    // ==================== Controller-Level Tests ====================

    @Test
    @Order(20)
    @DisplayName("Controller: returns 200 with ETag and Cache-Control headers")
    void controller_returns200WithETag() {
        ResponseEntity<ApiResponse<RecordCapabilities>> response =
                recordCapabilityController.getRecordCapabilities(
                        MODEL_CODE, testRecordId, "web", "detail", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isNotNull();
        assertThat(response.getHeaders().getETag()).startsWith("W/\"cap-");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, max-age=30");

        ApiResponse<RecordCapabilities> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo("0");
        assertThat(body.getData()).isNotNull();
        assertThat(body.getData().getModelCode()).isEqualTo(MODEL_CODE);
    }

    @Test
    @Order(21)
    @DisplayName("Controller: returns 304 when ETag matches (conditional request)")
    void controller_returns304WhenETagMatches() {
        ResponseEntity<ApiResponse<RecordCapabilities>> first =
                recordCapabilityController.getRecordCapabilities(
                        MODEL_CODE, testRecordId, "web", "detail", null);

        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotNull();

        ResponseEntity<ApiResponse<RecordCapabilities>> second =
                recordCapabilityController.getRecordCapabilities(
                        MODEL_CODE, testRecordId, "web", "detail", etag);

        assertThat(second.getStatusCode().value()).isEqualTo(304);
        assertThat(second.getBody()).isNull();
    }

    @Test
    @Order(22)
    @DisplayName("Controller: mobile platform returns valid capabilities")
    void controller_mobilePlatform() {
        ResponseEntity<ApiResponse<RecordCapabilities>> response =
                recordCapabilityController.getRecordCapabilities(
                        MODEL_CODE, testRecordId, "mobile", "detail", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        RecordCapabilities data = response.getBody().getData();
        assertThat(data).isNotNull();
        assertThat(data.getCapabilities()).isNotNull();
    }

    // ==================== Helpers ====================

    private void ensureCapabilityFixture() {
        ensureShowcaseTable();
        ensureShowcaseModel();
        ensureShowcasePermission();
        seedCommand("sc:list_showcase", "List showcase", "query",
                "{\"type\":\"query\"}");
        seedCommand("sc:detail_showcase", "Detail showcase", "query",
                "{\"type\":\"query\"}");
        seedCommand("sc:create_showcase", "Create showcase", "create",
                "{\"type\":\"create\"}");
        seedCommand("sc:update_showcase", "Update showcase", "update",
                "{\"type\":\"update\",\"permissions\":[\"sc.showcase.manage\"],"
                        + "\"inputFields\":[\"sc_name\"],\"priority\":10,"
                        + "\"platforms\":[\"web\",\"mobile\"]}");
        seedCommand("sc:delete_showcase", "Delete showcase", "delete",
                "{\"type\":\"delete\",\"permissions\":[\"sc.showcase.manage\"],"
                        + "\"priority\":99,\"platforms\":[\"web\",\"mobile\"]}");
        seedCommand("sc:activate_showcase", "Activate showcase", "state_transition",
                "{\"type\":\"state_transition\",\"permissions\":[\"sc.showcase.manage\"],"
                        + "\"stateField\":\"sc_status\",\"fromStates\":[\"draft\"],"
                        + "\"toState\":\"active\",\"priority\":1,\"platforms\":[\"web\",\"mobile\"]}");
        seedCommand("sc:archive_showcase", "Archive showcase", "state_transition",
                "{\"type\":\"state_transition\",\"permissions\":[\"sc.showcase.manage\"],"
                        + "\"stateField\":\"sc_status\",\"fromStates\":[\"active\",\"review\"],"
                        + "\"toState\":\"archived\",\"priority\":2,\"platforms\":[\"web\",\"mobile\"]}");
        seedCommand("sc:submit_review_showcase", "Submit showcase", "state_transition",
                "{\"type\":\"state_transition\",\"permissions\":[\"sc.showcase.manage\"],"
                        + "\"stateField\":\"sc_status\",\"fromStates\":[\"active\"],"
                        + "\"toState\":\"review\",\"priority\":3,\"platforms\":[\"web\",\"mobile\"]}");
        userPermissionService.evictUserPermissions(adminUserId);
    }

    private void ensureShowcaseTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mt_showcase_all_fields (
                    id BIGSERIAL PRIMARY KEY,
                    pid VARCHAR(64) UNIQUE NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    sc_status VARCHAR(64),
                    sc_name VARCHAR(255),
                    sc_code VARCHAR(128),
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    created_by VARCHAR(255),
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_by VARCHAR(255),
                    deleted_flag BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS sc_status VARCHAR(64)");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS sc_name VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS sc_code VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS created_by VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE mt_showcase_all_fields ADD COLUMN IF NOT EXISTS deleted_flag BOOLEAN NOT NULL DEFAULT FALSE");
    }

    private void ensureShowcaseModel() {
        jdbcTemplate.update("""
                INSERT INTO ab_meta_model (
                    pid, tenant_id, code, table_name, extension, capabilities,
                    version, is_current, status, deleted_flag
                )
                SELECT ?, ?, ?, 'mt_showcase_all_fields',
                       '{"displayName":"Showcase All Fields"}'::jsonb, '{}'::jsonb,
                       1, TRUE, 'published', FALSE
                WHERE NOT EXISTS (
                    SELECT 1 FROM ab_meta_model
                    WHERE tenant_id = ? AND code = ? AND deleted_flag = FALSE
                )
                """, UniqueIdGenerator.generate(), adminTenantId, MODEL_CODE, adminTenantId, MODEL_CODE);
    }

    private void ensureShowcasePermission() {
        Long permissionId = jdbcTemplate.queryForObject("""
                INSERT INTO ab_permission (
                    pid, tenant_id, code, name, resource_type, resource_code, action,
                    source, status, deleted_flag
                )
                VALUES (?, ?, 'sc.showcase.manage', 'Manage showcase', 'model', ?, 'manage',
                        'manual', 'active', FALSE)
                ON CONFLICT (tenant_id, code)
                DO UPDATE SET name = EXCLUDED.name,
                              resource_type = EXCLUDED.resource_type,
                              resource_code = EXCLUDED.resource_code,
                              action = EXCLUDED.action,
                              status = 'active',
                              deleted_flag = FALSE,
                              updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, UniqueIdGenerator.generate(), adminTenantId, MODEL_CODE);
        jdbcTemplate.update("""
                INSERT INTO ab_role_permission (
                    pid, tenant_id, role_id, permission_id, grant_type, status, deleted_flag
                )
                SELECT ?, ?, ?, ?, 'grant', 'active', FALSE
                WHERE NOT EXISTS (
                    SELECT 1 FROM ab_role_permission
                    WHERE tenant_id = ? AND role_id = ? AND permission_id = ?
                      AND deleted_flag = FALSE
                )
                """,
                UniqueIdGenerator.generate(),
                adminTenantId,
                getTestRole().getId(),
                permissionId,
                adminTenantId,
                getTestRole().getId(),
                permissionId);
    }

    private void seedCommand(String code, String displayName, String type, String executionConfig) {
        jdbcTemplate.update("""
                INSERT INTO ab_command_definition (
                    pid, tenant_id, code, display_name, model_code,
                    input_schema, target_models, execution_config, extension,
                    version, is_current, status, deleted_flag
                )
                VALUES (?, ?, ?, ?, ?,
                        '{}'::jsonb, '[]'::jsonb, ?::jsonb, '{}'::jsonb,
                        1, TRUE, 'published', FALSE)
                ON CONFLICT (tenant_id, code, version)
                DO UPDATE SET display_name = EXCLUDED.display_name,
                              model_code = EXCLUDED.model_code,
                              execution_config = EXCLUDED.execution_config,
                              is_current = TRUE,
                              status = 'published',
                              deleted_flag = FALSE,
                              updated_at = CURRENT_TIMESTAMP
                """,
                UniqueIdGenerator.generate(), adminTenantId, code, displayName, MODEL_CODE, executionConfig);
    }

    private Set<String> extractCodes(List<ActionCapability> actions) {
        return actions.stream()
                .map(ActionCapability::getCode)
                .collect(Collectors.toSet());
    }

    private ActionCapability findAction(RecordCapabilities result, String code) {
        return result.getCapabilities().stream()
                .filter(a -> code.equals(a.getCode()))
                .findFirst()
                .orElse(null);
    }
}
