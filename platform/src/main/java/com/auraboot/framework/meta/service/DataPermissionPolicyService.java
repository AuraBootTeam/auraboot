package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;

import java.util.List;

/**
 * Service for managing data permission policies.
 *
 * @since 5.1.0
 */
public interface DataPermissionPolicyService {

    DataPermissionPolicy create(DataPermissionPolicyCreateRequest request);

    DataPermissionPolicy getByPid(String pid);

    List<DataPermissionPolicy> listByModelCode(String modelCode);

    List<DataPermissionPolicy> listAll();

    DataPermissionPolicy update(String pid, DataPermissionPolicyCreateRequest request);

    void delete(String pid);

    void enable(String pid);

    void disable(String pid);

    void bindToRole(String policyPid, String rolePid);

    void unbindFromRole(String policyPid, String rolePid);

    /**
     * List all role bindings for a specific policy.
     */
    List<DataPermissionRoleBinding> listRoleBindings(String policyPid);

    /**
     * Resolves a role referenced by a policy binding for display enrichment:
     * same-tenant, not deleted. Returns null when the role pid is unknown or
     * unavailable in the current tenant (the caller treats that as denied).
     */
    com.auraboot.framework.rbac.entity.Role resolveBindableRole(String rolePid);

    /**
     * Get all enabled policies for a user (via their roles) on a model.
     */
    List<DataPermissionPolicy> getEffectivePolicies(Long tenantId, String modelCode, Long userId);

    /**
     * Preview the SQL filter that would be generated for a given user/model combination.
     *
     * @param modelCode model code
     * @param userId    user ID to test (if null, uses current user)
     * @return the SQL WHERE fragment or empty string
     */
    String previewRowFilter(String modelCode, Long userId);

    /** Filter and policy list evaluated for one local member in the same read snapshot. */
    record Preview(Long userId, String rowFilter, List<DataPermissionPolicy> effectivePolicies) { }
    Preview preview(String modelCode, Long userId);
}
