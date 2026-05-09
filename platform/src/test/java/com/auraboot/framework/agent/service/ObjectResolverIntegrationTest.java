package com.auraboot.framework.agent.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ObjectResolver: two-layer matching (exact/alias + fuzzy)
 * and command resolution via ab_command_definition.
 *
 * Uses real PostgreSQL — no mocks per project convention.
 * Relies on seed data: ab_object_alias (客户→crm_account), ab_meta_model (published models),
 * and ab_command_definition (crm:create_account etc.).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired
    ObjectResolver objectResolver;

    @Autowired
    DynamicDataMapper dynamicDataMapper;

    // Tenant that owns published commands (may differ from test tenant)
    private Long commandTenantId;
    private String sampleDisplayName;
    private String sampleDisplayModelCode;

    @BeforeEach
    void ensureIndexBuilt() {
        // Force rebuild to ensure test starts with a clean, populated index
        objectResolver.rebuildIndex();

        // Find a tenant that has crm_account commands for command resolution tests
        String sql = "SELECT DISTINCT tenant_id FROM ab_command_definition " +
                "WHERE model_code = 'crm_account' AND is_current = true " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());
        if (!rows.isEmpty()) {
            commandTenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        }

        String displaySql = "SELECT code, extension->>'displayName' AS display_name FROM ab_meta_model " +
                "WHERE extension->>'displayName' IS NOT NULL " +
                "AND extension->>'displayName' <> '' " +
                "AND status = 'published' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY code ASC LIMIT 1";
        List<Map<String, Object>> displayRows = dynamicDataMapper.selectByQueryWithoutTenant(displaySql, Map.of());
        if (!displayRows.isEmpty()) {
            sampleDisplayModelCode = (String) displayRows.get(0).get("code");
            sampleDisplayName = (String) displayRows.get(0).get("display_name");
        }
    }

    // ========== Exact Match Tests ==========

    @Test
    @Order(1)
    void resolve_exactMatch_alias() {
        // "客户" is an alias for crm_account in ab_object_alias
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), "查一下客户列表");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("crm_account");
        assertThat(result.getMatchType()).isEqualTo("alias");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.70);
        assertThat(result.getCandidates()).isEmpty();
    }

    @Test
    @Order(2)
    void resolve_exactMatch_modelCode() {
        // Using exact model code as input should match with highest confidence
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), "crm_account");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("crm_account");
        assertThat(result.getMatchType()).isEqualTo("exact");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    @Order(3)
    void resolve_exactMatch_displayName() {
        Assumptions.assumeTrue(sampleDisplayModelCode != null && sampleDisplayName != null,
                "No published model with displayName found");
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), sampleDisplayName);

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo(sampleDisplayModelCode);
        assertThat(result.getMatchType()).isEqualTo("alias");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.70);
    }

    // ========== Fuzzy Match Tests ==========

    @Test
    @Order(10)
    void resolve_fuzzyMatch_partialDisplayName() {
        // Derive a 2-char probe from a real sampleDisplayName harvested in
        // @BeforeEach so this test is robust against seed-data churn.
        // Hardcoded probes (e.g. "销售") were brittle: no guarantee any model
        // with that displayName exists in the published seed.
        Assumptions.assumeTrue(sampleDisplayName != null && sampleDisplayName.length() >= 2,
                "No published model with displayName ≥ 2 chars found");
        String probe = sampleDisplayName.substring(0, 2);

        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), "帮我看看" + probe + "相关的数据");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isNotNull();
        // Should produce a match (fuzzy or alias/exact if an alias exists)
        assertThat(result.getMatchType()).isIn("fuzzy", "alias", "exact");
        if ("fuzzy".equals(result.getMatchType())) {
            assertThat(result.getCandidates()).isNotEmpty();
            assertThat(result.getConfidence()).isLessThan(0.85);
        }
    }

    // ========== No Match Tests ==========

    @Test
    @Order(20)
    void resolve_noMatch() {
        // Use gibberish that won't match any model code, alias, or display name
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), "zzqwfxmknop utterly random gibberish");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getMatchType()).isEqualTo("none");
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.getCandidates()).isEmpty();
    }

    @Test
    @Order(21)
    void resolve_nullInput() {
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), null);

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getMatchType()).isEqualTo("none");
    }

    @Test
    @Order(22)
    void resolve_emptyInput() {
        ObjectResolver.ObjectResult result = objectResolver.resolve(
                getTestTenant().getId(), "   ");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getMatchType()).isEqualTo("none");
    }

    // ========== Command Resolution Tests ==========

    @Test
    @Order(30)
    void resolveCommand_findsCreateCommand() {
        Assumptions.assumeTrue(commandTenantId != null, "No tenant with crm_account commands found");
        String commandCode = objectResolver.resolveCommand(commandTenantId, "crm_account", "create");

        assertThat(commandCode).isNotNull();
        assertThat(commandCode).contains("account");
    }

    @Test
    @Order(31)
    void resolveCommand_findsUpdateCommand() {
        Assumptions.assumeTrue(commandTenantId != null, "No tenant with crm_account commands found");
        String commandCode = objectResolver.resolveCommand(commandTenantId, "crm_account", "update");

        assertThat(commandCode).isNotNull();
        assertThat(commandCode).contains("account");
    }

    @Test
    @Order(32)
    void resolveCommand_findsDeleteCommand() {
        Assumptions.assumeTrue(commandTenantId != null, "No tenant with crm_account commands found");
        String commandCode = objectResolver.resolveCommand(commandTenantId, "crm_account", "delete");

        assertThat(commandCode).isNotNull();
        assertThat(commandCode).contains("account");
    }

    @Test
    @Order(33)
    void resolveCommand_findsQueryCommand() {
        Assumptions.assumeTrue(commandTenantId != null, "No tenant with crm_account commands found");
        String commandCode = objectResolver.resolveCommand(commandTenantId, "crm_account", "query");

        assertThat(commandCode).isNotNull();
        assertThat(commandCode).contains("account");
    }

    @Test
    @Order(34)
    void resolveCommand_intentSynonym() {
        Assumptions.assumeTrue(commandTenantId != null, "No tenant with crm_account commands found");
        // "add" should map to "create" execution type
        String commandCode = objectResolver.resolveCommand(commandTenantId, "crm_account", "add");

        assertThat(commandCode).isNotNull();
    }

    @Test
    @Order(35)
    void resolveCommand_nullModel_returnsNull() {
        String commandCode = objectResolver.resolveCommand(
                getTestTenant().getId(), null, "create");

        assertThat(commandCode).isNull();
    }

    @Test
    @Order(36)
    void resolveCommand_unknownIntent_returnsNull() {
        String commandCode = objectResolver.resolveCommand(
                getTestTenant().getId(), "crm_account", "dance");

        assertThat(commandCode).isNull();
    }

    @Test
    @Order(37)
    void resolveCommand_nonExistentModel_returnsNull() {
        String commandCode = objectResolver.resolveCommand(
                getTestTenant().getId(), "nonexistent_model_xyz", "create");

        assertThat(commandCode).isNull();
    }

    // ========== Cache Tests ==========

    @Test
    @Order(40)
    void cache_secondCallUsesCache() {
        Long tenantId = getTestTenant().getId();

        // First call: triggers DB query and caches
        objectResolver.resolve(tenantId, "客户");

        // Second call: should use cache (no way to directly verify, but should not error)
        ObjectResolver.ObjectResult result = objectResolver.resolve(tenantId, "客户");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("crm_account");
    }

    @Test
    @Order(41)
    void cache_invalidateTriggersFreshLoad() {
        Long tenantId = getTestTenant().getId();

        // Populate cache
        objectResolver.resolve(tenantId, "客户");

        // Invalidate
        objectResolver.invalidateCache(tenantId);

        // Should still work (fresh load)
        ObjectResolver.ObjectResult result = objectResolver.resolve(tenantId, "客户");
        assertThat(result.getModelCode()).isEqualTo("crm_account");
    }
}
