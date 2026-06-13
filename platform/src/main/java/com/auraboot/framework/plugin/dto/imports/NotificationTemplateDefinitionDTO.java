package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for importing notification-template definitions from a plugin manifest
 * ({@code config/notification-templates.json}).
 *
 * <p>Notification templates are tenant-scoped data (table {@code ab_notification_template}),
 * keyed by {@code code} and looked up at delivery time by {@code NotificationService.send()}.
 * Shipping them as a plugin resource lets a plugin deliver its own BPMN/automation
 * notifications (e.g. an iot alarm process's {@code iot_alarm_notify} step) instead of the
 * platform logging "template not found, skipping". Imported per-tenant on plugin import,
 * upserted by {@code (tenant_id, code)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplateDefinitionDTO {

    /**
     * Template code — the lookup key used by {@code NotificationSendRequest.templateCode}
     * (e.g. a BPMN serviceTask's {@code smart:eventCode}). Required.
     */
    private String code;

    /**
     * Human-readable template name. Required.
     */
    private String name;

    /**
     * Legacy single channel: {@code in_app} / {@code email} / {@code sms}. Required
     * (kept for the {@code channel}-only delivery path). For multi-channel use {@link #channels}.
     */
    private String channel;

    /**
     * Optional JSON array of channel codes, e.g. {@code ["in_app","email"]}; overrides
     * {@link #channel} when set. Stored verbatim in {@code ab_notification_template.channels}.
     */
    private String channels;

    /**
     * Optional category: {@code business} / {@code system} / {@code approval}.
     * Defaults to {@code business} (the DB default) when absent.
     */
    private String category;

    /**
     * Subject template (optional for in-app; used by email). Supports {@code ${var}} substitution.
     */
    private String subjectTemplate;

    /**
     * Body template. Required. Supports {@code ${var}} substitution against the
     * notification variables (e.g. {@code ${severity}}, {@code ${alarmEventPid}}).
     */
    private String bodyTemplate;

    /**
     * Optional variable definitions as a JSON string (documentation/metadata).
     */
    private String variables;

    /**
     * Whether the template is enabled. Defaults to {@code true} — the runtime delivery
     * query only resolves enabled templates.
     */
    @Builder.Default
    private Boolean enabled = true;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    /**
     * Validate the template has the required fields ({@code code}, {@code name},
     * {@code channel}, {@code bodyTemplate}).
     */
    public boolean isValid() {
        return code != null && !code.isBlank()
                && name != null && !name.isBlank()
                && channel != null && !channel.isBlank()
                && bodyTemplate != null && !bodyTemplate.isBlank();
    }

    /** {@code true} unless {@code enabled} is explicitly {@code false}. */
    @JsonIgnore
    public boolean isEnabledOrDefault() {
        return enabled == null || enabled;
    }

    /** Unique key for dedup/preview: the template code. */
    @JsonIgnore
    public String getUniqueKey() {
        return code;
    }
}
