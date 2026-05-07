package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link CommandFieldMapExecutor} contract defense.
 *
 * <p>Verifies that {@code delete} / {@code update} commands missing {@code targetRecordId}
 * are rejected with {@link BusinessException} (BadParam) instead of silently falling
 * through to an INSERT with a half-built row (which previously only failed when the
 * target table happened to have NOT NULL constraints).
 *
 * <p>Uses the real {@code ab_scheduled_task} table (no model definition required —
 * the executor falls back to the model code as table name when no
 * {@code ModelDefinition} is registered, so we pass the physical table name as the
 * target model and verify against the same table).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CommandFieldMapExecutorIntegrationTest extends BaseIntegrationTest {

    private static final String TABLE = "ab_scheduled_task";

    @Autowired
    private CommandFieldMapExecutor executor;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final List<String> createdPids = new ArrayList<>();

    @AfterEach
    public void cleanupTestRows() {
        for (String pid : createdPids) {
            try {
                Map<String, Object> conditions = new HashMap<>();
                conditions.put("pid", pid);
                dynamicDataMapper.delete(TABLE, conditions);
            } catch (Exception e) {
                log.warn("Cleanup failed for pid {}: {}", pid, e.getMessage());
            }
        }
        createdPids.clear();
    }

    /** Insert a real row directly via DynamicDataMapper, returns the pid. */
    private String seedRow(Long tenantId, String namePrefix) {
        String pid = UniqueIdGenerator.generate();
        String name = namePrefix + "-" + pid;
        Map<String, Object> data = new HashMap<>();
        data.put("tenant_id", tenantId);
        data.put("pid", pid);
        data.put("name", name);
        data.put("task_type", "cron");
        data.put("handler_bean", "noopHandler");
        data.put("created_at", Instant.now());
        data.put("updated_at", Instant.now());
        int inserted = dynamicDataMapper.insert(TABLE, data);
        assertThat(inserted).isEqualTo(1);
        createdPids.add(pid);
        return pid;
    }

    private long countByPid(String pid) {
        Map<String, Object> params = new HashMap<>();
        params.put("pid", pid);
        Long n = dynamicDataMapper.countByQueryWithoutTenant(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE pid = #{params.pid}", params);
        return n == null ? 0L : n;
    }

    private String getName(String pid) {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("pid", pid);
        List<Map<String, Object>> rows = dynamicDataMapper.queryList(
                TABLE, List.of("pid", "name"),
                "pid = '" + pid + "'", null, 1, 0);
        return rows.isEmpty() ? null : (String) rows.get(0).get("name");
    }

    /** Returns (created_at, updated_at) as raw java.sql.Timestamp / Instant. */
    private Map<String, Object> getRow(String pid) {
        List<Map<String, Object>> rows = dynamicDataMapper.queryList(
                TABLE, List.of("pid", "name", "cron_expression", "created_at", "updated_at"),
                "pid = '" + pid + "'", null, 1, 0);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<BindingRule> nameMappingRule() {
        BindingRule rule = new BindingRule();
        rule.setRuleType("FIELD_MAP");
        rule.setTargetModel(TABLE); // no ModelDefinition registered → falls back to TABLE name
        rule.setSourceField("name");
        rule.setTargetField("name");
        return List.of(rule);
    }

    // ---------- 1. delete + targetRecordId → real delete ----------

    @Test
    @DisplayName("delete + targetRecordId → row physically removed (affected=1)")
    public void testDeleteWithTargetRecordId_actuallyDeletes() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = seedRow(tenantId, "fmx-del-ok");
        assertThat(countByPid(pid)).isEqualTo(1L);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete");
        request.setTargetRecordId(pid);

        Map<String, Object> result = executor.executeFieldMapPhase(
                nameMappingRule(), Map.of(), tenantId, request);

        assertThat(result).containsEntry(TABLE + "_deleted", 1);
        assertThat(countByPid(pid)).isEqualTo(0L);
        createdPids.remove(pid); // already gone
    }

    // ---------- 2. delete + missing targetRecordId → BadParam, NO insert ----------

    @Test
    @DisplayName("delete without targetRecordId → BusinessException(BadParam), no INSERT side-effect")
    public void testDeleteWithoutTargetRecordId_rejected() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String marker = "fmx-del-bad-" + UniqueIdGenerator.generate();

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete");
        // targetRecordId intentionally null

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", marker);

        assertThatThrownBy(() ->
                executor.executeFieldMapPhase(nameMappingRule(), payload, tenantId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(be.getMessage()).contains("targetRecordId").contains("delete");
                });

        // Critical: confirm NO row was inserted with our marker name
        Map<String, Object> params = new HashMap<>();
        params.put("name", marker);
        Long inserted = dynamicDataMapper.countByQueryWithoutTenant(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE name = #{params.name}", params);
        assertThat(inserted).as("delete-without-id must NOT fall through to INSERT").isEqualTo(0L);
    }

    // ---------- 3. update + targetRecordId → real update ----------

    @Test
    @DisplayName("update + targetRecordId → row name actually updated")
    public void testUpdateWithTargetRecordId_actuallyUpdates() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = seedRow(tenantId, "fmx-upd-ok");
        String newName = "fmx-upd-renamed-" + UniqueIdGenerator.generate();

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        request.setTargetRecordId(pid);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", newName);

        Map<String, Object> result = executor.executeFieldMapPhase(
                nameMappingRule(), payload, tenantId, request);

        assertThat(result).containsEntry(TABLE + "_updated", 1);
        assertThat(getName(pid)).isEqualTo(newName);
    }

    // ---------- 4. update + missing targetRecordId → BadParam ----------

    @Test
    @DisplayName("update without targetRecordId → BusinessException(BadParam), no INSERT side-effect")
    public void testUpdateWithoutTargetRecordId_rejected() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String marker = "fmx-upd-bad-" + UniqueIdGenerator.generate();

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        // targetRecordId intentionally null

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", marker);

        assertThatThrownBy(() ->
                executor.executeFieldMapPhase(nameMappingRule(), payload, tenantId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(be.getMessage()).contains("targetRecordId").contains("update");
                });

        Map<String, Object> params = new HashMap<>();
        params.put("name", marker);
        Long inserted = dynamicDataMapper.countByQueryWithoutTenant(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE name = #{params.name}", params);
        assertThat(inserted).as("update-without-id must NOT fall through to INSERT").isEqualTo(0L);
    }

    // ---------- 5. implicit phase: delete without targetRecordId → BadParam ----------

    @Test
    @DisplayName("implicit phase: delete without targetRecordId → BusinessException(BadParam)")
    public void testImplicitDeleteWithoutTargetRecordId_rejected() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String marker = "fmx-impl-del-bad-" + UniqueIdGenerator.generate();

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("delete");

        CommandDefinition command = new CommandDefinition();
        command.setCode("test-impl-delete");
        command.setModelCode(TABLE);

        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("type", "delete");

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", marker);

        assertThatThrownBy(() ->
                executor.executeImplicitFieldMapPhase(execConfig, payload, tenantId, request, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(be.getMessage()).contains("targetRecordId");
                });

        Map<String, Object> params = new HashMap<>();
        params.put("name", marker);
        Long inserted = dynamicDataMapper.countByQueryWithoutTenant(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE name = #{params.name}", params);
        assertThat(inserted).as("implicit delete-without-id must NOT fall through to INSERT").isEqualTo(0L);
    }

    // ==================== F-5: update silent-success regressions ====================

    /**
     * F-5 root-cause regression: UPDATE must refresh updated_at.
     * Before fix, the UPDATE branch did not set updated_at, leaving the audit
     * timestamp stale even though SET name=... did persist. This made the row
     * look "untouched" to any caller comparing created_at vs updated_at.
     */
    @Test
    @DisplayName("F-5: update + targetRecordId refreshes updated_at (audit timestamp not stale)")
    public void testUpdate_refreshesUpdatedAt() throws InterruptedException {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = seedRow(tenantId, "fmx-f5-ts");
        Map<String, Object> before = getRow(pid);
        assertThat(before).isNotNull();
        Object createdAt = before.get("created_at");
        Object updatedAtBefore = before.get("updated_at");

        // Sleep so any clock-resolution issue can't accidentally pass the test
        Thread.sleep(50);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        request.setTargetRecordId(pid);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "fmx-f5-ts-renamed");

        Map<String, Object> result = executor.executeFieldMapPhase(
                nameMappingRule(), payload, tenantId, request);
        assertThat(result).containsEntry(TABLE + "_updated", 1);

        Map<String, Object> after = getRow(pid);
        assertThat(after).isNotNull();
        assertThat((String) after.get("name")).isEqualTo("fmx-f5-ts-renamed");
        // updated_at must move forward; created_at must stay put
        assertThat(after.get("updated_at"))
                .as("updated_at should be refreshed after UPDATE")
                .isNotEqualTo(updatedAtBefore);
        assertThat(after.get("created_at"))
                .as("created_at must remain stable across UPDATE")
                .isEqualTo(createdAt);
    }

    /**
     * F-5 defense: UPDATE with non-existent targetRecordId must throw BadParam,
     * not silently return code=0 with affected=0. Hides "wrong tenant / typo'd id" bugs.
     */
    @Test
    @DisplayName("F-5: update against non-existent pid → BusinessException(BadParam), not silent affected=0")
    public void testUpdate_unknownPid_rejectsLoudly() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String ghostPid = "01GHOST" + UniqueIdGenerator.generate().substring(7); // 32-char fake pid

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        request.setTargetRecordId(ghostPid);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "ghost-rename");

        assertThatThrownBy(() ->
                executor.executeFieldMapPhase(nameMappingRule(), payload, tenantId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(be.getMessage()).contains("0 rows").contains(ghostPid);
                });
    }

    /**
     * F-5 implicit-phase regression: same root cause and defense for the implicit
     * inputFields path used by DSL list-page commands like admin:update_scheduled_task.
     * Recreates the exact request shape from the original incident.
     */
    @Test
    @DisplayName("F-5: implicit update via inputFields → row mutated AND updated_at refreshed")
    public void testImplicitUpdate_refreshesUpdatedAtAndMutatesRow() throws InterruptedException {
        Long tenantId = MetaContext.getCurrentTenantId();
        String pid = seedRow(tenantId, "fmx-f5-impl");
        Map<String, Object> before = getRow(pid);
        Object createdAt = before.get("created_at");
        Object updatedAtBefore = before.get("updated_at");
        Thread.sleep(50);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        request.setTargetRecordId(pid);

        CommandDefinition command = new CommandDefinition();
        command.setCode("test-impl-update");
        command.setModelCode(TABLE); // no ModelDefinition registered → executor uses TABLE as table name

        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("type", "update");
        // Mimic admin:update_scheduled_task inputFields shape
        execConfig.put("inputFields", List.of("name", "cron_expression"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "fmx-f5-impl-renamed");
        payload.put("cron_expression", "0 45 3 * * ?");

        Map<String, Object> result = executor.executeImplicitFieldMapPhase(
                execConfig, payload, tenantId, request, command);
        assertThat(result).containsEntry(TABLE + "_updated", 1);

        Map<String, Object> after = getRow(pid);
        assertThat((String) after.get("name")).isEqualTo("fmx-f5-impl-renamed");
        assertThat((String) after.get("cron_expression")).isEqualTo("0 45 3 * * ?");
        assertThat(after.get("updated_at"))
                .as("updated_at should be refreshed after implicit UPDATE")
                .isNotEqualTo(updatedAtBefore);
        assertThat(after.get("created_at")).isEqualTo(createdAt);
    }

    /**
     * F-5 defense: implicit UPDATE against non-existent pid must throw BadParam.
     */
    @Test
    @DisplayName("F-5: implicit update against non-existent pid → BusinessException(BadParam)")
    public void testImplicitUpdate_unknownPid_rejectsLoudly() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String ghostPid = "01GHOST" + UniqueIdGenerator.generate().substring(7);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("update");
        request.setTargetRecordId(ghostPid);

        CommandDefinition command = new CommandDefinition();
        command.setCode("test-impl-update-ghost");
        command.setModelCode(TABLE);

        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("type", "update");
        execConfig.put("inputFields", List.of("name"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "ghost-impl");

        assertThatThrownBy(() ->
                executor.executeImplicitFieldMapPhase(execConfig, payload, tenantId, request, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.BadParam);
                    assertThat(be.getMessage()).contains("0 rows");
                });
    }
}
