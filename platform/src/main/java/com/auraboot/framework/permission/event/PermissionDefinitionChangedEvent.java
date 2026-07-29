package com.auraboot.framework.permission.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** Published after a tenant permission definition is created, changed, or removed. */
@Getter
public class PermissionDefinitionChangedEvent extends ApplicationEvent {

    private final Long tenantId;
    private final String permissionCode;
    private final String operation;

    public PermissionDefinitionChangedEvent(
            Object source, Long tenantId, String permissionCode, String operation) {
        super(source);
        this.tenantId = tenantId;
        this.permissionCode = permissionCode;
        this.operation = operation;
    }
}
