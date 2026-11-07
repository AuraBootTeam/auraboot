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

/** Draft-owned authoring of direct native grants; compiled sources keep their own publish boundary. */
@Service
@RequiredArgsConstructor
public class RoleGrantFieldScopeService {
    private final RolePermissionMapper grants;
    private final PermissionService permissions;
    private final MetaModelService models;
    private final DataPermissionAuthoringAccess policies;
    private final RoleAuthorizationAuthoringAccess authoring;
    private final ObjectMapper json;

    List<Binding> catalog(Role role) {
        owned(role);
        var definitions = permissions.findAllActive().stream().filter(p -> p.getTenantId() == null
                || Objects.equals(p.getTenantId(), role.getTenantId())).collect(java.util.stream.Collectors.toMap(p -> p.getId(), p -> p));
        List<Binding> result = new ArrayList<>();
        for (var grant : grants.findByRole(role.getId())) {
            if (!Objects.equals(grant.getTenantId(), role.getTenantId()) || !Objects.equals(grant.getRoleId(), role.getId())) throw denied();
            var permission = definitions.get(grant.getPermissionId());
            if (permission == null || permission.getCode() == null || permission.getResourceCode() == null
                    || !Set.of("read", "create", "update").contains(Objects.toString(permission.getAction(), ""))
                    || !("model." + permission.getResourceCode() + "." + permission.getAction()).equals(permission.getCode())) continue;
            var model = models.getModelDefinition(permission.getResourceCode());
            if (model.isEmpty()) continue;
            if (grant.getPid() == null || grant.getPid().isBlank()) throw denied();
            var fields = models.getModelFields(permission.getResourceCode()).stream()
                    .filter(f -> f.getCode() != null && !Boolean.TRUE.equals(f.getPrimaryKey())
                            && !SystemFieldConstants.isSystemField(f.getCode())
                            && !SystemFieldConstants.isSystemField(f.getColumnName()))
                    .map(f -> new Field(f.getCode(), f.getDisplayName() != null ? f.getDisplayName() : f.getName()))
                    .sorted(Comparator.comparing(Field::code)).toList();
            var parsed = BusinessGrantFieldScope.parse(json, grant.getFieldScope() == null ? null : encode(grant.getFieldScope()), permission.getResourceCode());
            boolean editable = directlyEditable(grant);
            result.add(new Binding(grant.getPid(), permission.getCode(), permission.getResourceCode(),
                    model.get().getDisplayName(), editable, parsed.unrestricted(),
                    parsed.readable().stream().sorted().toList(), parsed.writable().stream().sorted().toList(), fields));
        }
        return result.stream().sorted(Comparator.comparing(Binding::bindingPid)).toList();
    }

    List<Reviewed> preview(Role role, List<Change> changes, Set<String> resultingCodes) {
        if (changes == null || changes.isEmpty()) return List.of();
        owned(role); authoring.requireWrite(); authoring.requireTarget(role.getId()); policies.requireWrite();
        if (changes.size() > 1000) throw denied();
        var catalog = catalog(role); var seen = new HashSet<String>(); List<Reviewed> result = new ArrayList<>();
        for (var change : changes) {
            if (change == null || change.bindingPid() == null || !seen.add(change.bindingPid())) throw denied();
            var binding = catalog.stream().filter(b -> b.bindingPid().equals(change.bindingPid())).findFirst().orElseThrow(RoleGrantFieldScopeService::denied);
            if (!binding.editable() || !resultingCodes.contains(binding.permissionCode())) throw denied();
            Set<String> allowed = binding.fields().stream().map(Field::code).collect(java.util.stream.Collectors.toSet());
            var reads = validate(change.readFields(), allowed); var writes = validate(change.writeFields(), allowed);
            if (binding.legacy() || !reads.equals(binding.readFields()) || !writes.equals(binding.writeFields())) result.add(new Reviewed(binding, reads, writes));
        }
        return List.copyOf(result);
    }

    void apply(Role role, List<Reviewed> changes) {
        if (changes.isEmpty()) return;
        owned(role); authoring.requireWrite(); authoring.requireTarget(role.getId()); policies.requireWrite();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
        for (var change : changes) {
            var scope = Map.of("modelCode", change.before().modelCode(), "readFields", change.readFields(), "writeFields", change.writeFields());
            var binding = grants.findByRole(role.getId()).stream().filter(row -> change.before().bindingPid().equals(row.getPid())).findFirst().orElseThrow(RoleGrantFieldScopeService::denied);
            if (!Objects.equals(binding.getTenantId(), role.getTenantId()) || !directlyEditable(binding)) throw denied();
            if (grants.updateDirectFieldScope(role.getTenantId(), role.getId(), change.before().bindingPid(), encode(scope), MetaContext.getCurrentUserId(), binding.getSourceRef()) != 1) throw denied();
        }
    }

    private static boolean directlyEditable(com.auraboot.framework.rbac.entity.RolePermission binding) {
        if (binding.getSnapshotId() != null || !"GRANT".equalsIgnoreCase(binding.getGrantType())) return false;
        if (binding.getSourceRef() == null) return true;
        // Host role copies are detached grants; their provenance is not an active publish owner.
        String[] parts = binding.getSourceRef().split(":", -1);
        return parts.length == 3 && "copy".equals(parts[0])
                && com.auraboot.framework.common.util.UniqueIdGenerator.isValid(parts[1])
                && com.auraboot.framework.common.util.UniqueIdGenerator.isValid(parts[2]);
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
