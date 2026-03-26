package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.UserProjectBinding;

import java.util.List;

/**
 * Service for managing user-project bindings (project membership).
 */
public interface UserProjectBindingService {

    /**
     * Add a user to a project (upsert).
     */
    void addMember(Long tenantId, Long userId, String projectPid, String bindingRole, Long operatorId);

    /**
     * Remove a user from a project.
     */
    void removeMember(Long tenantId, Long userId, String projectPid);

    /**
     * Get all members of a project.
     */
    List<UserProjectBinding> getProjectMembers(Long tenantId, String projectPid);

    /**
     * Get all projects the user is bound to.
     */
    List<UserProjectBinding> getUserProjects(Long tenantId, Long userId);

    /**
     * Get project PIDs the user is bound to (for data permission filtering).
     */
    List<String> getUserProjectPids(Long tenantId, Long userId);
}
