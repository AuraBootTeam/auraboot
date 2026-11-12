package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.dto.RoleGrantFields.*;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Draft-owned authoring of direct model-bound grants; compiled sources keep their own publish boundary. */
@Service
@RequiredArgsConstructor
public class RoleGrantFieldScopeService {
    private final RolePermissionMapper grants;
    private final PermissionService permissions;
    private final MetaModelService models;
    private final DataPermissionAuthoringAccess policies;
    private final RoleAuthorizationAuthoringAccess authoring;
    private final ObjectMapper json;

    record BusinessBoundary(String permissionCode, String model, String action) {}

    List<Binding> catalog(Role role, List<BusinessBoundary> boundaries) {
        owned(role);
        var definitions = definitions(role);
        List<Binding> result = new ArrayList<>();
        var seen = new HashSet<String>();
        for (var grant : grants.findByRole(role.getId())) {
            if (!Objects.equals(grant.getTenantId(), role.getTenantId()) || !Objects.equals(grant.getRoleId(), role.getId())) throw denied();
            var permission = definitions.get(grant.getPermissionId());
            if (!modelBoundPermission(permission, boundaries)) continue;
            if (grant.getPid() == null || grant.getPid().isBlank() || !seen.add(grant.getPid())) throw denied();
            for (String model : modelsFor(permission, boundaries)) {
                var binding = describe(permission, grant, model);
                if (binding != null) result.add(binding);
            }
        }
        return result.stream().sorted(Comparator.comparing(Binding::permissionCode).thenComparing(Binding::modelCode).thenComparing(Binding::bindingPid)).toList();
    }

    /** Project selected permissions without creating placeholder grants or changing live authority. */
    List<Binding> candidates(Role role, Set<String> resultingCodes, Set<String> newlyGranted, List<BusinessBoundary> boundaries) {
        var result = new ArrayList<>(catalog(role, boundaries).stream().filter(b -> resultingCodes.contains(b.permissionCode())).toList());
        var definitions = definitions(role);
        var bound = grants.findByRole(role.getId()).stream().map(row -> definitions.get(row.getPermissionId()))
                .filter(Objects::nonNull).map(p -> p.getCode()).collect(java.util.stream.Collectors.toSet());
        var pending = definitions.values().stream().filter(p -> modelBoundPermission(p, boundaries))
                .map(p -> p.getCode()).filter(code -> resultingCodes.contains(code) && newlyGranted.contains(code) && !bound.contains(code)).distinct().sorted().toList();
        for (String code : pending) {
            var permission = definition(role, code, definitions, boundaries);
            for (String model : modelsFor(permission, boundaries)) {
                var binding = describe(permission, null, model);
                if (binding != null) result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    List<Reviewed> preview(Role role, List<Change> changes, Set<String> resultingCodes) {
        return preview(role, changes, resultingCodes, Set.of(), List.of());
    }

    List<Reviewed> preview(Role role, List<Change> changes, Set<String> resultingCodes, Set<String> newlyGranted, List<BusinessBoundary> boundaries) {
        if (changes == null || changes.isEmpty()) return List.of();
        owned(role); authoring.requireWrite(); authoring.requireTarget(role.getId()); policies.requireWrite();
        if (changes.size() > 1000) throw denied();
        var catalog = candidates(role, resultingCodes, newlyGranted, boundaries);
        var definitions = definitions(role);
        var seen = new HashSet<String>(); List<Reviewed> result = new ArrayList<>();
        for (var change : changes) {
            if (change == null || (change.bindingPid() == null) == (change.permissionCode() == null)) throw denied();
            boolean pending = change.bindingPid() == null;
            String model = change.modelCode();
            if (model == null) {
                var definition = pending ? definition(role, change.permissionCode(), definitions, boundaries)
                        : grants.findByRole(role.getId()).stream().filter(row -> change.bindingPid().equals(row.getPid()))
                            .map(row -> definitions.get(row.getPermissionId())).filter(Objects::nonNull)
                            .findFirst().orElseThrow(RoleGrantFieldScopeService::denied);
                model = definition.getResourceCode();
            }
            String selectedModel = model;
            String key = (pending ? "permission:" + change.permissionCode() : "binding:" + change.bindingPid()) + ":" + selectedModel;
            if (!seen.add(key) || pending && !newlyGranted.contains(change.permissionCode())) throw denied();
            var binding = catalog.stream().filter(b -> selectedModel.equals(b.modelCode()) && (pending
                    ? b.bindingPid() == null && change.permissionCode().equals(b.permissionCode())
                    : change.bindingPid().equals(b.bindingPid()))).findFirst().orElseThrow(RoleGrantFieldScopeService::denied);
            if (!binding.editable() || !resultingCodes.contains(binding.permissionCode())) throw denied();
            if (pending && (change.expectedMetadataRevision() == null || !change.expectedMetadataRevision().matches("[a-f0-9]{64}"))) throw denied();
            if (change.expectedMetadataRevision() != null && !change.expectedMetadataRevision().equals(binding.metadataRevision())) throw conflict();
            Set<String> allowed = binding.fields().stream().map(Field::code).collect(java.util.stream.Collectors.toSet());
            var reads = validate(change.readFields(), allowed); var writes = validate(change.writeFields(), allowed);
            if (pending || !binding.configured() || binding.legacy() || !reads.equals(binding.readFields()) || !writes.equals(binding.writeFields())) result.add(new Reviewed(binding, reads, writes));
        }
        return List.copyOf(result);
    }

    void apply(Role role, List<Reviewed> changes, List<BusinessBoundary> boundaries) {
        if (changes.isEmpty()) return;
        owned(role); authoring.requireWrite(); authoring.requireTarget(role.getId()); policies.requireWrite();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
        var definitions = definitions(role);
        var grouped = changes.stream().collect(java.util.stream.Collectors.groupingBy(change ->
                change.before().bindingPid() != null ? "binding:" + change.before().bindingPid()
                        : "permission:" + change.before().permissionCode(), LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (var group : grouped.values()) {
            var before = group.getFirst().before();
            var matches = grants.findByRole(role.getId()).stream().filter(row -> {
                if (before.bindingPid() != null) return before.bindingPid().equals(row.getPid());
                var permission = definitions.get(row.getPermissionId());
                return permission != null && before.permissionCode().equals(permission.getCode());
            }).toList();
            if (matches.size() != 1) throw denied();
            var binding = matches.getFirst();
            if (!Objects.equals(binding.getTenantId(), role.getTenantId()) || !directlyEditable(binding)) throw denied();
            var permission = definitions.get(binding.getPermissionId());
            var models = modelsFor(permission, boundaries);
            var document = BusinessGrantFieldScope.decode(json, binding.getFieldScope() == null ? null : encode(binding.getFieldScope()));
            var next = new TreeMap<>(document.models());
            if (document.legacy()) {
                // Narrow one object without silently revoking another currently declared root.
                for (String model : models) {
                    if (describe(permission, binding, model) != null)
                        next.put(model, com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields.legacy());
                }
            }
            Set<String> seen = new HashSet<>();
            for (var change : group) {
                String model = change.before().modelCode();
                if (!models.contains(model) || !seen.add(model) || !before.permissionCode().equals(change.before().permissionCode())) throw denied();
                var current = describe(permission, binding, model);
                if (current == null || !Objects.equals(change.before().metadataRevision(), current.metadataRevision())) throw conflict();
                Set<String> allowed = current.fields().stream().map(Field::code).collect(java.util.stream.Collectors.toSet());
                var reads = validate(change.readFields(), allowed); var writes = validate(change.writeFields(), allowed);
                next.put(model, new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,
                        Set.copyOf(reads), Set.copyOf(writes)));
            }
            String scope = BusinessGrantFieldScope.encode(json, next, document.versionTwo());
            if (grants.updateDirectFieldScope(role.getTenantId(), role.getId(), binding.getPid(), scope,
                    MetaContext.getCurrentUserId(), binding.getSourceRef()) != 1) throw denied();
        }
    }

    private Map<Long,com.auraboot.framework.permission.dto.PermissionDTO> definitions(Role role) {
        return permissions.findAllActive().stream().filter(p -> p.getTenantId() == null || Objects.equals(p.getTenantId(), role.getTenantId()))
                .collect(java.util.stream.Collectors.toMap(p -> p.getId(), p -> p));
    }

    private com.auraboot.framework.permission.dto.PermissionDTO definition(Role role, String code,
            Map<Long,com.auraboot.framework.permission.dto.PermissionDTO> definitions, List<BusinessBoundary> boundaries) {
        var local = definitions.values().stream().filter(p -> code.equals(p.getCode()) && Objects.equals(p.getTenantId(), role.getTenantId())).toList();
        var eligible = local.isEmpty() ? definitions.values().stream().filter(p -> code.equals(p.getCode()) && p.getTenantId() == null).toList() : local;
        if (eligible.size() != 1 || !modelBoundPermission(eligible.getFirst(), boundaries)) throw denied();
        return eligible.getFirst();
    }

    private Binding describe(com.auraboot.framework.permission.dto.PermissionDTO permission,
            com.auraboot.framework.rbac.entity.RolePermission grant, String modelCode) {
        var model = models.getModelDefinition(modelCode);
        if (model.isEmpty()) return null;
        var metadata = models.getModelFields(modelCode);
        var fields = metadata.stream().filter(f -> f.getCode() != null && !Boolean.TRUE.equals(f.getPrimaryKey())
                        && !SystemFieldConstants.isSystemField(f.getCode()) && !SystemFieldConstants.isSystemField(f.getColumnName()))
                .map(f -> new Field(f.getCode(), f.getDisplayName() != null ? f.getDisplayName() : f.getName()))
                .sorted(Comparator.comparing(Field::code)).toList();
        var selected = BusinessGrantFieldScope.select(json, grant == null || grant.getFieldScope() == null ? null : encode(grant.getFieldScope()), modelCode);
        var parsed = selected.orElseGet(() -> new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false, Set.of(), Set.of()));
        String revision;
        try {
            var state = new LinkedHashMap<String,Object>(); state.put("model", model.get());
            state.put("fields", metadata.stream().sorted(Comparator.comparing(f -> Objects.toString(f.getCode(), ""))).toList());
            byte[] bytes = json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(state);
            revision = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot compute grant field metadata revision", failure);
        }
        return new Binding(grant == null ? null : grant.getPid(), permission.getCode(), modelCode,
                model.get().getDisplayName(), grant == null || directlyEditable(grant), parsed.unrestricted(),
                parsed.readable().stream().sorted().toList(), parsed.writable().stream().sorted().toList(), fields, revision, selected.isPresent());
    }

    private static boolean modelBoundPermission(com.auraboot.framework.permission.dto.PermissionDTO permission, List<BusinessBoundary> boundaries) {
        return !modelsFor(permission, boundaries).isEmpty();
    }

    private static List<String> modelsFor(com.auraboot.framework.permission.dto.PermissionDTO permission, List<BusinessBoundary> boundaries) {
        if (permission == null || permission.getCode() == null || permission.getCode().isBlank()
                || "*".equals(permission.getCode()) || "meta.command.execute".equals(permission.getCode())
                || permission.getResourceCode() == null
                || !permission.getResourceCode().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || permission.getAction() == null || permission.getAction().isBlank()) return List.of();
        var domain = new com.auraboot.framework.permission.entity.Permission();
        domain.setCode(permission.getCode()); domain.setResourceType(permission.getResourceType());
        domain.setAction(permission.getAction()); domain.setExtension(permission.getExtension());
        var declared = DomainPermissionPolicy.models(domain);
        if (!declared.isEmpty()) {
            // Public domain ownership already covers native reads without a child Command.
            // Internal command effects remain execution-only and are never authoring roots.
            return declared.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof List<?> actions && !actions.isEmpty())
                    .map(entry -> (String) entry.getKey()).sorted().toList();
        }
        if (("model." + permission.getResourceCode() + "." + permission.getAction()).equals(permission.getCode()))
            return Set.of("read", "create", "update").contains(permission.getAction()) ? List.of(permission.getResourceCode()) : List.of();
        if (!("operation".equalsIgnoreCase(permission.getResourceType()) || "data".equalsIgnoreCase(permission.getResourceType()))) return List.of();
        // Only actual registered business roots, never caller-provided model names.
        return boundaries.stream().filter(boundary -> permission.getCode().equals(boundary.permissionCode())
                        && (DomainPermissionPolicy.permits(permission, boundary.model(), boundary.action())
                            || permission.getAction().equals(boundary.action()) || "manage".equals(permission.getAction())))
                .map(BusinessBoundary::model).distinct().sorted().toList();
    }

    private static com.auraboot.framework.exception.BusinessException conflict() {
        return new com.auraboot.framework.exception.BusinessException(com.auraboot.framework.common.constant.ResponseCode.CasVersionConflict);
    }

    private static boolean directlyEditable(com.auraboot.framework.rbac.entity.RolePermission binding) {
        if (!"GRANT".equalsIgnoreCase(binding.getGrantType())) return false;
        var owner = RoleGrantSourceOwnership.of(binding);
        return owner == RoleGrantSourceOwnership.DIRECT || owner == RoleGrantSourceOwnership.DETACHED_COPY;
    }

    private static List<String> validate(List<String> fields, Set<String> allowed) {
        if (fields == null || fields.size() > 1000 || fields.stream().anyMatch(f -> !BusinessGrantFieldScope.validFieldCode(f) || !allowed.contains(f))
                || new HashSet<>(fields).size() != fields.size()) throw denied();
        return fields.stream().sorted().toList();
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw denied(); }
    }
    private static void owned(Role role) {
        if (role == null || !Objects.equals(role.getTenantId(), MetaContext.getCurrentTenantId())) throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Grant field configuration is invalid or not directly editable"); }
}
