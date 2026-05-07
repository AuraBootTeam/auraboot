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
}
