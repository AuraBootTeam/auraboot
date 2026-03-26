package com.auraboot.framework.permission.listener;

import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.SubjectPermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Permission Cache Eviction Listener
 *
 * <p>Listens to permission-related events and evicts caches accordingly.
 *
 * <p>Event-Driven Cache Eviction Matrix:
 * <pre>
 * Event                          | Eviction Target
 * -------------------------------|----------------------------------
 * RolePermissionChangedEvent     | user-permissions (all users of role)
 * UserRoleChangedEvent           | user-permissions (specific user)
 * SubjectPermissionChangedEvent  | subject-evaluation (specific subject)
 * </pre>
 *
 * <p>Cache Hierarchy:
 * <pre>
 * L1: user-permissions:{userId}
 * L2: subject-evaluation:{subjectType}:{subjectId}:{userId}
 * L3: subject-declarations:{subjectType}:{subjectId}
 * </pre>
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionCacheEvictionListener {

    private final UserPermissionService userPermissionService;
    private final SubjectPermissionService subjectPermissionService;

    /**
     * Handle Role-Permission binding changed event
     *
     * <p>Triggers:
     * <ul>
     *   <li>Role-Permission binding created</li>
     *   <li>Role-Permission binding updated</li>
     *   <li>Role-Permission binding deleted</li>
     * </ul>
     *
     * <p>Eviction Strategy:
     * <ol>
     *   <li>Query all users assigned to the role</li>
     *   <li>Evict each user's permission cache</li>
     * </ol>
     *
     * @param event Role-Permission changed event
     */
    @EventListener
    public void onRolePermissionChanged(RolePermissionChangedEvent event) {
        Long roleId = event.getRoleId();
        String operation = event.getOperation();

        log.info("Role-Permission changed, evicting cache: roleId={}, operation={}",
            roleId, operation);

        // Evict all users' permission cache for this role
        userPermissionService.evictRoleUsers(roleId);
    }

    /**
     * Handle User-Role binding changed event
     *
     * <p>Triggers:
     * <ul>
     *   <li>User-Role binding created</li>
     *   <li>User-Role binding deleted</li>
     * </ul>
     *
     * <p>Eviction Strategy:
     * <ul>
     *   <li>Evict the user's permission cache</li>
     * </ul>
     *
     * @param event User-Role changed event
     */
    @EventListener
    public void onUserRoleChanged(UserRoleChangedEvent event) {
        Long userId = event.getUserId();
        String operation = event.getOperation();

        log.info("User-Role changed, evicting cache: userId={}, operation={}",
            userId, operation);

        // Evict the user's permission cache
        userPermissionService.evictUserPermissions(userId);
    }

    /**
     * Handle Subject-Permission declaration changed event
     *
     * <p>Triggers:
     * <ul>
     *   <li>Subject-Permission declaration created</li>
     *   <li>Subject-Permission declaration updated</li>
     *   <li>Subject-Permission declaration deleted</li>
     * </ul>
     *
     * <p>Eviction Strategy:
     * <ul>
     *   <li>Evict the subject's evaluation cache (all users)</li>
     * </ul>
     *
     * @param event Subject-Permission changed event
     */
    @EventListener
    public void onSubjectPermissionChanged(SubjectPermissionChangedEvent event) {
        String subjectType = event.getSubjectType();
        Long subjectId = event.getSubjectId();
        String operation = event.getOperation();

        log.info("Subject-Permission changed, evicting cache: {}:{}, operation={}",
            subjectType, subjectId, operation);

        // Evict the subject's evaluation cache
        subjectPermissionService.evictSubjectEvaluations(subjectType, subjectId);
    }
}
