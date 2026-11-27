package com.auraboot.framework.permission.engine.model;

import java.util.List;
import java.util.Set;

/** Complete matching grants for one actor and host-loaded record; never a wire authorization flag. */
public record BusinessGrantDecision(PermissionResult result, Long tenantId, Long userId, Long memberId,
                                    String modelCode, String recordPid, List<Grant> grants) {
    public BusinessGrantDecision {
        grants = List.copyOf(grants);
    }

    public record Grant(Long grantId, Long roleId, String permissionCode, Fields fields) { }

    /** Null storage retains legacy authorization; explicit empty sets permit no fields. */
    public record Fields(boolean unrestricted, Set<String> readable, Set<String> writable) {
        public Fields {
            readable = Set.copyOf(readable);
            writable = Set.copyOf(writable);
        }
        public static Fields legacy() { return new Fields(true, Set.of(), Set.of()); }
    }

    public boolean canWrite(String field) {
        return result.granted() && grants.stream().anyMatch(grant ->
                grant.fields().unrestricted() || grant.fields().writable().contains(field));
    }

    public boolean canRead(String field) {
        return result.granted() && grants.stream().anyMatch(grant ->
                grant.fields().unrestricted() || grant.fields().readable().contains(field));
    }
}
