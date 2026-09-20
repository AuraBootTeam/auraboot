package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.CasVersionConflictException;
import com.auraboot.framework.exception.CasVersionRequiredException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandExecutorUtils;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Re-checks and transaction-holds the version of the client-named command target.
 *
 * <p>This phase deliberately runs after every authorization gate and the atomic idempotency claim,
 * but before any mutation or plugin handler. The {@code FOR SHARE} row lock remains held by the
 * caller's command transaction until commit/rollback, closing the gap between the earlier boundary
 * observation and the write phases.</p>
 */
@Component
@Order(535)
@RequiredArgsConstructor
public class CommandTargetVersionLockPhase implements CommandPhase {

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    @Override
    public String name() {
        return "target_version_lock";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        if (ctx.getRequest() == null
                || ctx.getCommand() == null
                || !StringUtils.hasText(ctx.getCommand().getModelCode())
                || !StringUtils.hasText(ctx.getRequest().getTargetRecordId())) {
            return true;
        }
        if (usesCurrentStateLock(ctx)) return false;
        if (ctx.getRequest().getExpectedVersion() == null) {
            // Only commands that declare casRequired in their executionConfig are strict
            // legacy mutations; entering execute fail-closes with the platform conflict
            // code instead of silently skipping the concurrency boundary. All other
            // update/delete commands proceed without a version (broad policy: the
            // owner opts individual value-editing commands into CAS).
            return !isStrictLegacyMutation(ctx.getRequest(), ctx.getCommand());
        }
        return false;
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        boolean currentStateLock = usesCurrentStateLock(ctx);
        if (!currentStateLock && ctx.getRequest().getExpectedVersion() == null) {
            if (isStrictLegacyMutation(ctx.getRequest(), ctx.getCommand())) {
                throw new CasVersionRequiredException(
                        "Strict existing-target mutation requires expectedVersion",
                        Map.of(
                                "modelCode", ctx.getCommand().getModelCode(),
                                "recordPid", ctx.getRequest().getTargetRecordId(),
                                "errorCode", ConflictException.ConflictCodes.CAS_VERSION_REQUIRED
                        ));
            }
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Command target version lock requires an active transaction");
        }
        String modelCode = ctx.getCommand().getModelCode();
        String tableName = metaModelService.getTableName(modelCode);
        FieldDefinition primaryKey = metaModelService.getPrimaryKeyField(modelCode);
        String primaryKeyColumn = primaryKey == null ? null : primaryKey.getColumnName();
        if (!StringUtils.hasText(primaryKeyColumn) && primaryKey != null) {
            primaryKeyColumn = primaryKey.getCode();
        }
        CommandExecutorUtils.validateSqlIdentifier(tableName, "command target version table");
        CommandExecutorUtils.validateSqlIdentifier(
                primaryKeyColumn, "command target version primary key");

        // SECURITY: selectByQueryWithoutTenant bypasses the tenant interceptor. This query carries
        // an explicit tenant_id predicate and binds both tenant and pid; identifiers are resolved
        // from MetaModelService and independently validated above.
        String sql = "SELECT row_version FROM " + tableName
                + " WHERE tenant_id = #{params.tenantId}"
                + " AND " + primaryKeyColumn + " = #{params.targetRecordPid}"
                + " FOR SHARE";
        Map<String, Object> params = Map.of(
                "tenantId", ctx.getTenantId(),
                "targetRecordPid", ctx.getRequest().getTargetRecordId());
        List<Map<String, Object>> rows = currentStateLock
                ? dynamicDataMapper.selectTargetVersionForUpdate(tableName, primaryKeyColumn,
                        ctx.getTenantId(), ctx.getRequest().getTargetRecordId())
                : dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
        Long authoritative = resolveVersion(rows);
        Integer requested = ctx.getRequest().getExpectedVersion();
        if (authoritative == null || (!currentStateLock && requested.longValue() != authoritative)) {
            throw new CasVersionConflictException(
                    "Command target version conflict (expected " + requested
                            + ", current " + (authoritative == null ? "unavailable" : authoritative)
                            + ")",
                    Map.of(
                            "modelCode", ctx.getCommand().getModelCode(),
                            "recordPid", ctx.getRequest().getTargetRecordId(),
                            "expectedVersion", requested == null ? "unspecified" : requested,
                            "currentVersion", authoritative == null ? "unavailable" : authoritative,
                            "errorCode", ConflictException.ConflictCodes.CAS_VERSION_CONFLICT
                    ));
        }
        ctx.setTargetRecordVersion(authoritative);
    }

    /**
     * Explicit intent commands may judge the latest lifecycle state instead of a stale
     * progress version. Keep an exclusive row lock through StateCheckPhase and commit:
     * skipping CAS alone would let a completed task be cancelled after a stale state read.
     * This policy is server-owned and restricted to bounded state transitions; ordinary
     * value edits retain their existing expectedVersion contract.
     */
    private boolean usesCurrentStateLock(CommandPipelineContext ctx) {
        String config = ctx.getCommand().getExecutionConfig();
        if (!StringUtils.hasText(config)) return false;
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return false; // Preserve the existing legacy policy for unrelated malformed config.
        }
        if (root == null || !"current_state".equals(
                root.path("options").path("targetVersionPolicy").asText())) return false;
        if (!"state_transition".equals(root.path("type").asText())
                || root.path("casRequired").asBoolean(false)) {
            throw new IllegalArgumentException(
                    "current_state targetVersionPolicy requires a non-strict state_transition command");
        }
        var fromStates = root.path("fromStates");
        if (!StringUtils.hasText(root.path("stateField").asText())
                || !StringUtils.hasText(root.path("toState").asText())
                || !fromStates.isArray() || fromStates.isEmpty()
                || root.has("stateTransitionRules")) {
            throw new IllegalArgumentException(
                    "current_state targetVersionPolicy requires stateField, non-empty fromStates and toState");
        }
        for (var state : fromStates) {
            if (!state.isTextual() || !StringUtils.hasText(state.asText())) {
                throw new IllegalArgumentException("current_state requires textual non-empty fromStates");
            }
        }
        return true;
    }

    /**
     * CAS is opt-in per command: an update/delete only requires expectedVersion when the
     * command's executionConfig declares {@code "casRequired": true}. Intent-style commands
     * (recompute, publish, delete) and background writers stay version-free unless the
     * owner opts them in; a missing or malformed executionConfig defaults to opt-out.
     */
    private boolean isStrictLegacyMutation(
            com.auraboot.framework.meta.dto.CommandExecuteRequest request,
            com.auraboot.framework.meta.entity.CommandDefinition command) {
        if (!"UPDATE".equalsIgnoreCase(request.getOperationType())
                && !"DELETE".equalsIgnoreCase(request.getOperationType())) {
            return false;
        }
        String executionConfig = command == null ? null : command.getExecutionConfig();
        if (!StringUtils.hasText(executionConfig)) {
            return false;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(executionConfig)
                    .path("casRequired")
                    .asBoolean(false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return false;
        }
    }

    private Long resolveVersion(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() != 1) {
            return null;
        }
        Object value = rows.get(0).get("row_version");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
