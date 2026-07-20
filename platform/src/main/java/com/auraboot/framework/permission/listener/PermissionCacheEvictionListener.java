package com.auraboot.framework.permission.listener;

import com.auraboot.framework.permission.event.PermissionDefinitionChangedEvent;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
 * PermissionDefinitionChanged    | permission-catalog (tenant)
 * RolePermissionChangedEvent     | role-permission + derived user snapshots
 * UserRoleChangedEvent           | user-role + effective user snapshot
 * </pre>
 *
 * <p>Cache Hierarchy:
 * <pre>
 * permission-catalog (tenant)
 * user-role-snapshots (tenant + user)
 * role-permission-snapshots (tenant + role + date)
 * user-permissions (tenant + user + date)
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRolePermissionChanged(RolePermissionChangedEvent event) {
        Long roleId = event.getRoleId();
        String operation = event.getOperation();

        log.info("Role-Permission changed, evicting cache: roleId={}, operation={}",
            roleId, operation);

        // Evict all users' permission cache for this role
        if (event.getTenantId() != null) {
            userPermissionService.evictRoleUsers(event.getTenantId(), roleId);
        } else {
            userPermissionService.evictRoleUsers(roleId);
        }
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserRoleChanged(UserRoleChangedEvent event) {
        Long userId = event.getUserId();
        String operation = event.getOperation();

        log.info("User-Role changed, evicting cache: userId={}, operation={}",
            userId, operation);

        // Evict the user's permission cache
        if (event.getTenantId() != null) {
            userPermissionService.evictUserPermissions(event.getTenantId(), userId);
        } else {
            userPermissionService.evictUserPermissions(userId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPermissionDefinitionChanged(PermissionDefinitionChangedEvent event) {
        log.info("Permission definition changed, evicting tenant catalog: tenantId={}, code={}, operation={}",
                event.getTenantId(), event.getPermissionCode(), event.getOperation());
        userPermissionService.evictPermissionDefinitions(event.getTenantId());
    }

}
