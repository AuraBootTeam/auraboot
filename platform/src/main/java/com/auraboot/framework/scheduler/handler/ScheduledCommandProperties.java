package com.auraboot.framework.scheduler.handler;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-owned delegations. Scheduled-task params cannot select identities or commands. */
@Data
@Component
@ConfigurationProperties(prefix = "aura.scheduler.commands", ignoreUnknownFields = false)
public class ScheduledCommandProperties {
    private boolean enabled;
    private List<Binding> bindings = List.of();

    @Data
    public static class Binding {
        private String code;
        private String taskPid;
        private Long tenantId;
        private String actorUserPid;
        private String commandCode;
        private String modelCode;
        private String targetRecordPid;
    }
}
