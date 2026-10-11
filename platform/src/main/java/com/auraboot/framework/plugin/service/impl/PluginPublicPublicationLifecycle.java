package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.auraboot.framework.openplatform.service.*;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Adapts code-keyed immutable publications to the existing plugin ownership ledger. */
@Service
@RequiredArgsConstructor
public class PluginPublicPublicationLifecycle {
    private final OpenApiPublicationMapper publications;
    private final OpenApiPublicationService resources;
    private final OpenApiQueryPublicationService queries;
    private final OpenApiPublicationValidator resourceValidator;
    private final OpenApiQueryValidator queryValidator;
    private final PluginResourceMapper tracking;
    private final PluginRecordMapper plugins;
    private final UserPermissionService permissions;
    private final ObjectMapper json;

    @Transactional
    public void importDeclarations(PluginManifestExtended manifest, ImportRequest request,
                                   ImportExecuteResult result, String pluginPid, String importId, Long tenantId) {
        requireAdmin(tenantId);
        if (!manifest.validateResources()) throw new PluginException("Invalid public publication declarations");
        List<OpenApiResourceContract> pending = new ArrayList<>(
                manifest.getPublicResources() == null ? List.of() : manifest.getPublicResources());
        while (!pending.isEmpty()) {
            Set<String> pendingCodes = new HashSet<>();
            pending.forEach(contract -> pendingCodes.add(contract.code()));
            var ready = pending.stream().filter(contract -> contract.fields().values().stream()
                    .map(OpenApiResourceContract.Field::referenceResource).filter(Objects::nonNull)
                    .noneMatch(code -> !code.equals(contract.code()) && pendingCodes.contains(code))).toList();
            if (ready.isEmpty()) throw new PluginException("Public resource reference cycle requires already published targets");
            for (var contract : ready) {
                importOne(ResourceType.PUBLIC_RESOURCE, contract.code(), contract, manifest, request,
                        result, pluginPid, importId, tenantId);
            }
            pending.removeAll(ready);
        }
        for (var contract : manifest.getPublicQueries() == null ? List.<OpenApiQueryContract>of() : manifest.getPublicQueries()) {
            importOne(ResourceType.PUBLIC_QUERY, contract.code(), contract, manifest, request,
                    result, pluginPid, importId, tenantId);
        }
    }

    private void importOne(ResourceType type, String code, Object contract, PluginManifestExtended manifest,
                           ImportRequest request, ImportExecuteResult result, String pluginPid,
                           String importId, Long tenantId) {
        String key = storageCode(type, code);
        var current = publications.lock(tenantId, key);
        var owned = tracking.lockByTypeAndCode(tenantId, type.code(), code);
        Map<String, Object> before = current == null ? null : state(current);
        if (current != null) {
            var identity = owned == null ? null : plugins.findIdentityIncludingDeleted(tenantId, owned.getPluginPid());
            boolean sameOwner = identity != null && manifest.getPluginId().equals(identity.getPluginId());
            boolean protectedState = !sameOwner || owned.getOwnershipTypeEnum() == OwnershipType.USER_CLAIMED
                    || Boolean.TRUE.equals(owned.getUserModified()) || !sameState(owned.getImportSnapshot(), before);
            if (protectedState) {
                if (request.getConflictStrategy() == ImportRequest.ConflictStrategy.SKIP
                        || request.getConflictStrategy() == ImportRequest.ConflictStrategy.OVERWRITE_SAFE && sameOwner) {
                    result.incrementResourceCount(type, ResourceAction.SKIP);
                    return;
                }
                throw new PluginException("Public publication is unmanaged, claimed, or modified: " + code);
            }
            if (request.getConflictStrategy() == ImportRequest.ConflictStrategy.ERROR) {
                throw new PluginException("Public publication already exists: " + code);
            }
            if (request.getConflictStrategy() == ImportRequest.ConflictStrategy.SKIP) {
                result.incrementResourceCount(type, ResourceAction.SKIP);
                return;
            }
        } else if (owned != null) {
            throw new PluginException("Tracked public publication is missing: " + code);
        }
        Map<String, Object> previous = before == null ? null : new LinkedHashMap<>();
        if (previous != null) {
            previous.put("publication", before);
            previous.put("tracking", json.convertValue(owned, new TypeReference<Map<String, Object>>() { }));
        }
        ResourceAction action = current == null ? ResourceAction.CREATE : ResourceAction.UPDATE;
        Object currentContract = current == null ? null : decode(current.draft(), type);
        if (contract.equals(currentContract) && current.activeVersion() != null
                && contract.equals(decode(publications.snapshot(tenantId, key, current.activeVersion()), type))) {
            validateActive(type, contract, tenantId);
            if ("published".equals(current.status())) action = ResourceAction.SKIP;
            else {
                publications.activate(tenantId, key, current.activeVersion(), "published", actor());
                publications.audit(tenantId, key, null, "plugin_reactivate", actor(), current.draftRevision());
            }
        } else if (type == ResourceType.PUBLIC_RESOURCE) {
            var draft = resources.saveDraft(code, new OpenApiPublicationService.DraftRequest(
                    current == null ? 0 : current.draftRevision(), (OpenApiResourceContract) contract));
            resources.publish(code, draft.draftRevision());
        } else {
            var draft = queries.saveDraft(code, new OpenApiQueryPublicationService.DraftRequest(
                    current == null ? 0 : current.draftRevision(), (OpenApiQueryContract) contract));
            queries.publish(code, draft.draftRevision());
        }
        Map<String, Object> after = state(publications.lock(tenantId, key));
        var record = owned == null ? PluginResource.builder().pid(UlidGenerator.generate()).tenantId(tenantId)
                .resourceType(type.code()).resourceCode(code).resourceName(code)
                .ownershipType(OwnershipType.SHARED.code()).createdAt(Instant.now()).build() : owned;
        record.setPluginPid(pluginPid);
        record.setImportId(importId);
        record.setAction(action.code());
        record.setPreviousState(previous);
        record.setCurrentState(after);
        record.setImportSnapshot(after);
        record.setLastSyncVersion(manifest.getVersion());
        record.setUserModified(false);
        record.setUserModifiedAt(null);
        record.setSequence(type.getImportOrder());
        record.setUpdatedAt(Instant.now());
        if (record.getId() == null) tracking.insert(record); else tracking.updateById(record);
        result.incrementResourceCount(type, action);
        // Public codes are not resource PIDs; tracking is recorded without inventing an identifier.
    }

    @Transactional
    public Map<String, Object> currentState(Long tenantId, ResourceType type, String code) {
        var row = publications.lock(tenantId, storageCode(type, code));
        return row == null ? Map.of() : state(row);
    }

    /** Retain ownership even after withdrawal so reinstall cannot claim another plugin's code. */
    @Transactional
    public void withdraw(PluginResource resource) {
        requireAdmin(resource.getTenantId());
        var type = resource.getResourceTypeEnum();
        var row = publications.lock(resource.getTenantId(), storageCode(type, resource.getResourceCode()));
        if (row == null) throw new PluginException("Public publication is missing: " + resource.getResourceCode());
        if (type == ResourceType.PUBLIC_RESOURCE) resources.withdraw(resource.getResourceCode(), row.draftRevision());
        else queries.withdraw(resource.getResourceCode(), row.draftRevision());
        var withdrawn = state(publications.lock(resource.getTenantId(), row.code()));
        resource.setAction(ResourceAction.DELETE.code());
        resource.setCurrentState(withdrawn);
        resource.setImportSnapshot(withdrawn);
        resource.setUserModified(false);
        resource.setUpdatedAt(Instant.now());
        tracking.updateById(resource);
    }

    @Transactional
    public void restore(PluginResource resource) {
        requireAdmin(resource.getTenantId());
        var type = resource.getResourceTypeEnum();
        var row = publications.lock(resource.getTenantId(), storageCode(type, resource.getResourceCode()));
        var currentOwner = tracking.lockByTypeAndCode(resource.getTenantId(), type.code(), resource.getResourceCode());
        if (currentOwner == null || !Objects.equals(resource.getId(), currentOwner.getId())
                || !Objects.equals(resource.getPluginPid(), currentOwner.getPluginPid())
                || !Objects.equals(resource.getImportId(), currentOwner.getImportId())) {
            throw new PluginException("Public publication rollback owner changed: " + resource.getResourceCode());
        }
        if (currentOwner.getOwnershipTypeEnum() == OwnershipType.USER_CLAIMED
                || Boolean.TRUE.equals(currentOwner.getUserModified())) {
            throw new PluginException("Public publication is claimed or modified: " + resource.getResourceCode());
        }
        if (row == null || !sameState(resource.getImportSnapshot(), state(row))) {
            throw new PluginException("Public publication changed since import: " + resource.getResourceCode());
        }
        if (resource.getPreviousState() == null) {
            withdraw(resource);
            return;
        }
        var previous = json.convertValue(resource.getPreviousState().get("publication"),
                new TypeReference<Map<String, Object>>() { });
        var priorTracking = json.convertValue(resource.getPreviousState().get("tracking"), PluginResource.class);
        Object definition = convertContract(previous.get("draft"), type);
        Integer version = previous.get("activeVersion") == null ? null : Integer.valueOf(previous.get("activeVersion").toString());
        if ("published".equals(previous.get("status"))) {
            if (version == null) throw new PluginException("Published rollback snapshot is missing");
            Object active = decode(publications.snapshot(resource.getTenantId(), row.code(), version), type);
            validateActive(type, active, resource.getTenantId());
        }
        if (publications.updateDraft(resource.getTenantId(), row.code(), encode(definition), actor(), row.draftRevision()) != 1) {
            throw new PluginException("Public publication revision changed during rollback");
        }
        publications.activate(resource.getTenantId(), row.code(), version, previous.get("status").toString(), actor());
        publications.audit(resource.getTenantId(), row.code(), null, "plugin_rollback", actor(), row.draftRevision() + 1);
        var restored = state(publications.lock(resource.getTenantId(), row.code()));
        priorTracking.setId(resource.getId());
        priorTracking.setCurrentState(restored);
        priorTracking.setImportSnapshot(restored);
        priorTracking.setUpdatedAt(Instant.now());
        tracking.updateById(priorTracking);
    }

    private void validateActive(ResourceType type, Object contract, Long tenantId) {
        if (type == ResourceType.PUBLIC_RESOURCE) {
            var definition = (OpenApiResourceContract) contract;
            resources.requireSourceBinding(tenantId, definition, resourceValidator.validate(definition));
        } else queryValidator.validate(tenantId, (OpenApiQueryContract) contract);
    }

    private Map<String, Object> state(OpenApiPublicationMapper.PublicationRow row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("draft", readTree(row.draft()));
        state.put("draftRevision", Long.toString(row.draftRevision()));
        state.put("activeVersion", row.activeVersion() == null ? null : row.activeVersion().toString());
        state.put("status", row.status());
        return json.convertValue(state, new TypeReference<Map<String, Object>>() { });
    }

    private boolean sameState(Map<String, Object> left, Map<String, Object> right) {
        return left != null && right != null && json.valueToTree(left).equals(json.valueToTree(right));
    }

    private String storageCode(ResourceType type, String code) {
        if (!type.isPublicPublication()) throw new IllegalArgumentException("Public resource type required");
        return type == ResourceType.PUBLIC_QUERY ? "query:" + code : code;
    }

    private Object decode(String value, ResourceType type) {
        return convertContract(readTree(value), type);
    }

    private Object convertContract(Object value, ResourceType type) {
        return type == ResourceType.PUBLIC_RESOURCE ? json.convertValue(value, OpenApiResourceContract.class)
                : json.convertValue(value, OpenApiQueryContract.class);
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String value) {
        try { return json.readTree(value); }
        // CATCH: stored definitions must fail closed without changing ownership or publication state.
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new PluginException("Stored public publication is invalid", failure);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        // CATCH: serialization errors abort the surrounding plugin transaction.
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new PluginException("Public publication cannot be serialized", failure);
        }
    }

    private void requireAdmin(Long tenantId) {
        if (!Objects.equals(tenantId, MetaContext.getCurrentTenantId()) || tenantId == null
                || MetaContext.getCurrentUserId() == null || !permissions.hasPermission(
                MetaContext.getCurrentUserId(), MetaPermission.SYS_CONNECTOR_MANAGE)) {
            throw new AccessDeniedException("Tenant publication administrator required");
        }
        actor();
    }

    private String actor() {
        String actor = MetaContext.getCurrentUserPid();
        if (actor == null) throw new AccessDeniedException("Human publication administrator required");
        return actor;
    }
}
