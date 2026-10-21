package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.auraboot.framework.meta.dto.NamedQueryFieldBatchRequest;
import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.meta.dto.NamedQueryUpdateRequest;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.constant.StatusConstants;
import org.springframework.cache.Cache;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/** Imports named queries and preserves their status transitions. */
final class PluginNamedQueryImporter {
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final NamedQueryService namedQueryService;
    private final ObjectMapper objectMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final Logger log;
    private final PluginImportSupport support;

    PluginNamedQueryImporter(
            PluginResourceImporterImpl facadeReceiver,
            NamedQueryService namedQueryService,
            ObjectMapper objectMapper,
            NamedQueryMapper namedQueryMapper,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.namedQueryService = namedQueryService;
        this.objectMapper = objectMapper;
        this.namedQueryMapper = namedQueryMapper;
        this.log = log;
        this.support = support;
    }

    public boolean checkNamedQueryExists(Long tenantId, String code) {
        try {
            NamedQueryDTO query = namedQueryService.findByCode(code);
            return query != null;
        } catch (Exception e) {
            log.debug("checkNamedQueryExists treating exception as 'not exists' for code={}: {}",
                    PluginImportSupport.logSafe(code), PluginImportSupport.logSafe(e.toString()));
            return false;
        }
    }

    public PluginResource importNamedQuery(NamedQueryDefinitionDTO dto, String pluginPid, String importId,
                                           Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = facadeReceiver.checkNamedQueryExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Named query already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    null, null, dto.getCode(), dto.getEffectiveTitle(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.NAMED_QUERY, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.NAMED_QUERY, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    null, null, dto.getCode(), dto.getEffectiveTitle(), ResourceAction.SKIP, null, null);
        }

        Map<String, Object> currentState = buildNamedQueryState(dto);
        List<NamedQueryFieldRequest> clonedFields = cloneNamedQueryFields(dto.getFields(), dto.getCode());

        if (exists) {
            // Use mapper directly to bypass Spring cache — stale cache can return a PID that no
            // longer exists in the DB (e.g. after uninstall/reinstall in test environments).
            NamedQuery existingEntity = namedQueryMapper.findByCode(dto.getCode());
            if (existingEntity == null) {
                // Cache was stale — the NQ was deleted; fall through to CREATE.
                exists = false;
            } else {
            String targetStatus = dto.getStatus() != null ? dto.getStatus() : existingEntity.getStatus();

            // Ensure query is editable before updating SQL/fields.
            ensureNamedQueryEditable(existingEntity.getPid());

            NamedQueryUpdateRequest updateRequest = new NamedQueryUpdateRequest();
            updateRequest.setTitle(dto.getEffectiveTitle());
            updateRequest.setDescription(dto.getDescription());
            updateRequest.setResourceCode(dto.getResourceCode());
            updateRequest.setActionCode(dto.getActionCode());
            updateRequest.setFromSql(dto.getFromSql());
            updateRequest.setBaseWhere(dto.getBaseWhere());
            updateRequest.setDefaultOrder(dto.getDefaultOrder());
            updateRequest.setTags(dto.getTags());
            updateRequest.setMetadata(dto.getMetadata());
            updateRequest.setPolicy(dto.getPolicy());
            updateRequest.setValidateSql(dto.getValidateSql() == null || dto.getValidateSql());
            updateRequest.setCheckPermissions(dto.getCheckPermissions() == null || dto.getCheckPermissions());
            namedQueryService.update(existingEntity.getPid(), updateRequest);

            if (clonedFields != null && !clonedFields.isEmpty()) {
                NamedQueryFieldBatchRequest fieldBatchRequest = new NamedQueryFieldBatchRequest();
                fieldBatchRequest.setOperationType("set");
                fieldBatchRequest.setClearExisting(true);
                fieldBatchRequest.setSource("plugin");
                fieldBatchRequest.setValidateFields(true);
                fieldBatchRequest.setSkipDuplicates(false);
                fieldBatchRequest.setFields(clonedFields);
                namedQueryService.batchSaveFields(dto.getCode(), fieldBatchRequest);
            }

            transitionNamedQueryStatus(existingEntity.getPid(), targetStatus);

            // A6: the status dance (ensure editable → update → republish) can restore
            // from_sql on ab_named_query from a stale version snapshot, silently
            // reverting the imported SQL. Re-assert the imported fromSql after the
            // transition so the executed query matches the plugin source; skip the
            // write when they already agree.
            NamedQuery updated = namedQueryMapper.findByCode(dto.getCode());
            if (updated != null
                    && dto.getFromSql() != null
                    && !dto.getFromSql().equals(updated.getFromSql())) {
                updated.setFromSql(dto.getFromSql());
                namedQueryMapper.updateById(updated);
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    updated != null ? updated.getPid() : null,
                    updated != null ? updated.getId() : null,
                    dto.getCode(), dto.getEffectiveTitle(),
                    ResourceAction.UPDATE, null, currentState);
            }
        }

        NamedQueryCreateRequest createRequest = new NamedQueryCreateRequest();
        createRequest.setCode(dto.getCode());
        createRequest.setTitle(dto.getEffectiveTitle());
        createRequest.setDescription(dto.getDescription());
        createRequest.setResourceCode(dto.getResourceCode());
        createRequest.setActionCode(dto.getActionCode());
        createRequest.setFromSql(dto.getFromSql());
        createRequest.setBaseWhere(dto.getBaseWhere());
        createRequest.setDefaultOrder(dto.getDefaultOrder());
        createRequest.setStatus(normalizeNamedQueryStatus(dto.getStatus()));
        createRequest.setFields(clonedFields);
        createRequest.setTags(dto.getTags());
        createRequest.setMetadata(dto.getMetadata());
        createRequest.setPolicy(dto.getPolicy());
        createRequest.setValidateSql(dto.getValidateSql() == null || dto.getValidateSql());
        createRequest.setCheckPermissions(dto.getCheckPermissions() == null || dto.getCheckPermissions());

        NamedQueryDTO created = namedQueryService.create(createRequest);
        // Mark all fields as PLUGIN-sourced for source-aware reimport
        namedQueryService.markFieldsAsPluginSource(dto.getCode());
        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveTitle(),
                ResourceAction.CREATE, null, currentState);
    }

    private Map<String, Object> buildNamedQueryState(NamedQueryDefinitionDTO dto) {
        Map<String, Object> state = new HashMap<>();
        state.put("code", dto.getCode());
        state.put("title", dto.getEffectiveTitle());
        state.put("description", dto.getDescription());
        state.put("resourceCode", dto.getResourceCode());
        state.put("actionCode", dto.getActionCode());
        state.put("fromSql", dto.getFromSql());
        state.put("status", normalizeNamedQueryStatus(dto.getStatus()));
        if (dto.getBaseWhere() != null) {
            state.put("baseWhere", dto.getBaseWhere());
        }
        if (dto.getDefaultOrder() != null) {
            state.put("defaultOrder", dto.getDefaultOrder());
        }
        if (dto.getFields() != null) {
            state.put("fields", dto.getFields());
        }
        if (dto.getTags() != null) {
            state.put("tags", dto.getTags());
        }
        if (dto.getMetadata() != null) {
            state.put("metadata", dto.getMetadata());
        }
        if (dto.getPolicy() != null) {
            state.put("policy", dto.getPolicy());
        }
        return state;
    }

    private List<NamedQueryFieldRequest> cloneNamedQueryFields(List<NamedQueryFieldRequest> fields, String queryCode) {
        if (fields == null) {
            return null;
        }
        List<NamedQueryFieldRequest> cloned = objectMapper.convertValue(fields,
                new TypeReference<List<NamedQueryFieldRequest>>() {});
        // Validate required properties instead of auto-filling defaults
        for (NamedQueryFieldRequest field : cloned) {
            String fieldCode = field.getFieldCode();
            if (fieldCode == null || fieldCode.isBlank()) {
                throw new PluginException("Named query '" + queryCode + "': field is missing 'code'/'fieldCode'");
            }
            if (field.getColumnExpr() == null || field.getColumnExpr().isBlank()) {
                throw new PluginException("Named query '" + queryCode + "', field '" + fieldCode
                        + "': missing 'columnExpr'. Add \"columnExpr\": \"" + fieldCode + "\" if it matches the SQL alias");
            }
            if (field.getDataType() == null || field.getDataType().isBlank()) {
                throw new PluginException("Named query '" + queryCode + "', field '" + fieldCode
                        + "': missing 'dataType'. Must be one of: string, number, date, boolean, json, array");
            }
        }
        return cloned;
    }

    private String normalizeNamedQueryStatus(String status) {
        if (status == null || status.isBlank()) {
            return "draft";
        }
        if ("enabled".equalsIgnoreCase(status)) {
            return "published";
        }
        if ("disabled".equalsIgnoreCase(status)) {
            return "archived";
        }
        return status.toLowerCase();
    }

    private boolean isNamedQueryEditableStatus(String status) {
        String normalized = normalizeNamedQueryStatus(status);
        return StatusConstants.DRAFT.equals(normalized) || StatusConstants.TESTING.equals(normalized);
    }

    private void ensureNamedQueryEditable(String queryPid) {
        NamedQueryDTO current = namedQueryService.findByPid(queryPid);
        if (!isNamedQueryEditableStatus(current.getStatus())) {
            transitionNamedQueryStatus(queryPid, "draft");
        }
    }

    private void transitionNamedQueryStatus(String queryPid, String targetStatus) {
        String target = normalizeNamedQueryStatus(targetStatus);
        NamedQueryDTO current = namedQueryService.findByPid(queryPid);
        String currentStatus = normalizeNamedQueryStatus(current.getStatus());

        if (currentStatus.equals(target)) {
            return;
        }

        // First, normalize to DRAFT to guarantee deterministic transitions.
        int guard = 0;
        while (!StatusConstants.DRAFT.equals(currentStatus) && guard++ < 8) {
            switch (currentStatus) {
                case "testing" -> namedQueryService.updateStatus(queryPid, "draft");
                case "published" -> namedQueryService.updateStatus(queryPid, "deprecated");
                case "deprecated" -> namedQueryService.updateStatus(queryPid, "archived");
                case "archived" -> namedQueryService.updateStatus(queryPid, "draft");
                default -> throw new PluginException("Unsupported named query status: " + currentStatus);
            }
            currentStatus = normalizeNamedQueryStatus(namedQueryService.findByPid(queryPid).getStatus());
        }

        if (guard >= 8 && !StatusConstants.DRAFT.equals(currentStatus)) {
            throw new PluginException("Failed to normalize named query status to DRAFT: " + queryPid);
        }

        // Transition from DRAFT to target.
        if (StatusConstants.DRAFT.equals(target)) {
            return;
        }
        if (StatusConstants.ARCHIVED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "archived");
            return;
        }
        if (StatusConstants.TESTING.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            return;
        }
        if (StatusConstants.PUBLISHED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            namedQueryService.updateStatus(queryPid, "published");
            return;
        }
        if (StatusConstants.DEPRECATED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            namedQueryService.updateStatus(queryPid, "published");
            namedQueryService.updateStatus(queryPid, "deprecated");
            return;
        }

        throw new PluginException("Unsupported target named query status: " + target);
    }

}
