package com.auraboot.framework.permission.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Subject-Permission Declaration Changed Event
 * 
 * <p>Published when:
 * <ul>
 *   <li>Subject-Permission declaration created</li>
 *   <li>Subject-Permission declaration updated</li>
 *   <li>Subject-Permission declaration deleted</li>
 * </ul>
 * 
 * <p>Cache Eviction:
 * <ul>
 *   <li>Evict subject-evaluation cache for the specific subject (all users)</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Getter
public class SubjectPermissionChangedEvent extends ApplicationEvent {
    
    private final String subjectType;  // MENU, PAGE, BUTTON, QUERY, WORKFLOW
    private final Long subjectId;
    private final Long permissionId;
    private final String operation;  // CREATE, UPDATE, DELETE
    
    public SubjectPermissionChangedEvent(
            Object source, 
            String subjectType, 
            Long subjectId, 
            Long permissionId,
            String operation) {
        super(source);
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.permissionId = permissionId;
        this.operation = operation;
    }
}
