package com.auraboot.framework.user.service;

import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.exception.UserException;

import java.util.Collection;
import java.util.List;

/**
 * @author 高海军 帝奇 Apr 6, 2015 5:07:09 PM
 */
public interface UserService {


    public User signUp(String email, String rawPassword, String displayName) throws UserException;

    default User signUp(String email, String rawPassword) throws UserException {
        return signUp(email, rawPassword, null);
    }

    public User signIn(String email, String password) throws UserException;


    public void signOut(Long userId) ;


    public User findByUserId(Long userId);

    public User findByUserName(String userName);


    User findByEmail(String email);

    public void update(User user);

    /**
     * 根据业务ID查询用户
     * @param pid 业务ID
     * @return 用户信息
     */
    User findByPid(String pid);

    /**
     * Batch find users by IDs.
     */
    List<User> findByUserIds(Collection<Long> userIds);

    /**
     * Search active human users within the given tenant by a case-insensitive keyword match
     * against display name / user name / email. Returns a safe, picker-oriented projection —
     * password and other sensitive fields are never included.
     *
     * @param tenantId required; scopes the search via ab_tenant_member
     * @param keyword  raw keyword (may be null/blank for "any"); wildcarded server-side
     * @param size     hard upper bound on returned rows (clamped to [1, 200])
     */
    List<UserSearchDTO> searchInTenant(Long tenantId, String keyword, int size);

    /**
     * Look up a single user by PID, restricted to the given tenant. Returns the
     * same safe projection as {@link #searchInTenant} (password etc never included).
     * Returns null when the user does not exist or is not a member of the tenant.
     */
    UserSearchDTO findInTenantByPid(Long tenantId, String pid);
}
