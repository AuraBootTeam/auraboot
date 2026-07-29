package com.auraboot.framework.permission.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * User-Role Binding Changed Event
 * 
 * <p>Published when:
 * <ul>
 *   <li>User-Role binding created</li>
 *   <li>User-Role binding deleted</li>
 * </ul>
 * 
 * <p>Cache Eviction:
 * <ul>
 *   <li>Evict user-permissions cache for the specific user</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Getter
public class UserRoleChangedEvent extends ApplicationEvent {

    private final Long tenantId;
    private final Long userId;
    private final Long roleId;
    private final String operation;  // CREATE, DELETE
    
    public UserRoleChangedEvent(Object source, Long userId, Long roleId, String operation) {
        this(source, null, userId, roleId, operation);
    }

    public UserRoleChangedEvent(
            Object source, Long tenantId, Long userId, Long roleId, String operation) {
        super(source);
        this.tenantId = tenantId;
        this.userId = userId;
        this.roleId = roleId;
        this.operation = operation;
    }
}
