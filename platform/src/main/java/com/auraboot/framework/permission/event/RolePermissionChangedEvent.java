package com.auraboot.framework.permission.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Role-Permission Binding Changed Event
 * 
 * <p>Published when:
 * <ul>
 *   <li>Role-Permission binding created</li>
 *   <li>Role-Permission binding updated</li>
 *   <li>Role-Permission binding deleted</li>
 * </ul>
 * 
 * <p>Cache Eviction:
 * <ul>
 *   <li>Evict user-permissions cache for all users of the role</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Getter
public class RolePermissionChangedEvent extends ApplicationEvent {
    
    private final Long roleId;
    private final Long permissionId;
    private final String operation;  // CREATE, UPDATE, DELETE
    
    public RolePermissionChangedEvent(Object source, Long roleId, Long permissionId, String operation) {
        super(source);
        this.roleId = roleId;
        this.permissionId = permissionId;
        this.operation = operation;
    }
}
