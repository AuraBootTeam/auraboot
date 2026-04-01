package com.auraboot.framework.rbac.service;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;

import java.util.List;

/**
 * Service for managing role memberships.
 * Phase 1: internally converts between member IDs and user IDs
 * since ab_user_role still uses user_id.
 */
public interface RoleMemberService {

    /**
     * Get paginated list of members assigned to a role.
     */
    PaginationResult<RoleMemberDTO> getMembers(Long roleId, int pageNum, int pageSize);

    /**
     * Add members to a role (batch). Skips already-assigned members.
     *
     * @param roleId     role ID
     * @param memberPids list of tenant member PIDs (string)
     */
    void addMembers(Long roleId, List<String> memberPids);

    /**
     * Remove members from a role (batch).
     *
     * @param roleId     role ID
     * @param memberPids list of tenant member PIDs (string)
     */
    void removeMembers(Long roleId, List<String> memberPids);

    /**
     * Get candidate members (not yet assigned to this role) for the "Add Member" dialog.
     * Optionally filtered by keyword (name/email).
     */
    List<RoleMemberDTO> getCandidates(Long roleId, String keyword);
}
