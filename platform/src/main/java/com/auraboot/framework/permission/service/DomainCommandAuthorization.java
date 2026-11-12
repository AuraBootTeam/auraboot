package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** One function decision per application invocation; record and field policies remain record-local. */
@Service
@RequiredArgsConstructor
public class DomainCommandAuthorization {
    private final PermissionSnapshotCache snapshots;
    private final RolePermissionMapper bindings;
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final class Frame {
        final Long tenant = MetaContext.getCurrentTenantId(), user = MetaContext.getCurrentUserId(), member = MetaContext.getCurrentMemberId();
        final Map<String, List<RolePermissionMapper.BusinessGrantRow>> grants = new HashMap<>();
        final Map<String, List<RolePermissionMapper.BusinessGrantRow>> candidateGrants = new HashMap<>();
        void verify() {
            if (!Objects.equals(tenant, MetaContext.getCurrentTenantId()) || !Objects.equals(user, MetaContext.getCurrentUserId())
                    || !Objects.equals(member, MetaContext.getCurrentMemberId())) throw denied();
        }
    }
    public <T> T withinInvocation(Supplier<T> operation) {
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame());
        try { return operation.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public boolean isDomain(String code) {
        return !DomainPermissionPolicy.models(snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code)).isEmpty();
    }

    /** Null leaves non-domain products on their existing authorization path. */
    public Boolean authorize(String code) {
        var permission = snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code);
        if (DomainPermissionPolicy.models(permission).isEmpty()) return null;
        if (MetaContext.getCurrentTenantId() == null || MetaContext.getCurrentUserId() == null || MetaContext.getCurrentMemberId() == null)
            throw denied();
        var rows = grants(code);
        return !rows.isEmpty() && rows.stream().allMatch(row -> "grant".equals(row.getGrantType()));
    }
    public List<RolePermissionMapper.BusinessGrantRow> grants(String code) {
        Frame frame = CURRENT.get();
        if (frame != null) {
            frame.verify();
            return frame.grants.computeIfAbsent(code, ignored -> load(code));
        }
        return load(code);
    }
    /** Shared application boundary for public native CRUD, including empty results. */
    public void requireResource(String model, String action) {
        if (!MetaContext.exists() || !snapshots.hasDomainModel(MetaContext.getCurrentTenantId(), model)) return;
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(model)) return;
        if (MetaContext.getCurrentUserId() == null || MetaContext.getCurrentMemberId() == null) throw denied();
        for (String code : snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, action)) {
            var rows = grants(code);
            if (!rows.isEmpty() && rows.stream().allMatch(row -> "grant".equals(row.getGrantType()))) return;
        }
        throw denied();
    }

    private List<RolePermissionMapper.BusinessGrantRow> load(String code) {
        var alternatives = snapshots.domainPermissionAlternatives(MetaContext.getCurrentTenantId(), code);
        if (alternatives == null || alternatives.isEmpty()) alternatives = List.of(code);
        List<RolePermissionMapper.BusinessGrantRow> result = new ArrayList<>();
        for (String candidate : alternatives) {
            var rows = candidateGrants(candidate);
            // Denying a higher operation does not remove a separately granted lower one.
            rows.stream().filter(row -> candidate.equals(code) || "grant".equals(row.getGrantType())).forEach(result::add);
        }
        return result.stream().collect(java.util.stream.Collectors.toMap(RolePermissionMapper.BusinessGrantRow::getId,
                row -> row, (first, second) -> first, LinkedHashMap::new)).values().stream().toList();
    }

    private List<RolePermissionMapper.BusinessGrantRow> candidateGrants(String code) {
        Frame frame = CURRENT.get();
        if (frame == null) return readCandidateGrants(code);
        frame.verify();
        // Retain raw denies: inheritance filtering depends on the requested level.
        return frame.candidateGrants.computeIfAbsent(code, this::readCandidateGrants);
    }

    private List<RolePermissionMapper.BusinessGrantRow> readCandidateGrants(String code) {
        var rows = bindings.findBusinessGrants(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(), MetaContext.getCurrentMemberId(), code);
        if (rows == null) throw denied();
        return List.copyOf(rows);
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Business domain authorization context is unavailable"); }
}
