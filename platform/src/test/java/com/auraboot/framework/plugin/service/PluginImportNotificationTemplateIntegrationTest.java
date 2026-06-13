package com.auraboot.framework.plugin.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.NotificationTemplateDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for F2: the {@code NOTIFICATION_TEMPLATE} plugin-import resource type, against a
 * real PostgreSQL DB.
 *
 * <p>Proves a plugin can ship notification templates so its BPMN/automation notifications deliver
 * instead of {@code NotificationService.send()} logging "template not found, skipping". Imports a
 * manifest carrying only {@code notificationTemplates} and asserts the row lands in
 * {@code ab_notification_template}, is resolvable by {@code getByCode}, and re-import upserts by
 * {@code (tenant_id, code)} rather than inserting a duplicate.
 *
 * <p>The end-to-end delivery (alarm process → role fan-out → in-app notification) is proven by the
 * iot {@code IotAlarmWorkflowBridgeIT}; this isolates the platform import mechanism.
 */
class PluginImportNotificationTemplateIntegrationTest extends BaseIntegrationTest {

    private static final String CODE = "it_f2_alarm_notify";

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private NotificationTemplateService notificationTemplateService;

    @Autowired
    private NotificationTemplateMapper notificationTemplateMapper;

    private PluginManifestExtended manifest(String body) {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setPluginId("it.f2.notiftpl");
        m.setNamespace("itf2");
        m.setVersion("1.0.0");
        m.setNotificationTemplates(List.of(
                NotificationTemplateDefinitionDTO.builder()
                        .code(CODE)
                        .name("F2 IT alarm notify")
                        .channel("in_app")
                        .category("system")
                        .subjectTemplate("Alarm (${severity})")
                        .bodyTemplate(body)
                        .variables("[\"alarmEventPid\",\"severity\"]")
                        .enabled(true)
                        .build()));
        return m;
    }

    private ImportRequest overwriteRequest() {
        return ImportRequest.builder()
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .build();
    }

    private long templateRowCount() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return notificationTemplateMapper.selectCount(new QueryWrapper<NotificationTemplate>()
                .eq("tenant_id", tenantId)
                .eq("code", CODE));
    }

    @Test
    @DisplayName("importing a notificationTemplates manifest creates a template resolvable by code")
    void import_createsTemplate_resolvableByCode() {
        ImportExecuteResult result =
                pluginImportService.executeFromManifest(manifest("Alarm ${alarmEventPid} severity ${severity}"),
                        overwriteRequest());

        assertThat(result.isSuccess())
                .as("import failed: %s", result.getErrorMessage()).isTrue();

        NotificationTemplate template = notificationTemplateService.getByCode(CODE);
        assertThat(template).as("template resolvable by code after import").isNotNull();
        assertThat(template.getBodyTemplate()).isEqualTo("Alarm ${alarmEventPid} severity ${severity}");
        assertThat(template.getChannel()).isEqualTo("in_app");
        assertThat(template.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("re-import upserts the template by (tenant, code) — single row, body updated")
    void reimport_upsertsByCode_noDuplicate() {
        pluginImportService.executeFromManifest(manifest("v1 body"), overwriteRequest());
        assertThat(templateRowCount()).as("one row after first import").isEqualTo(1L);

        ImportExecuteResult second =
                pluginImportService.executeFromManifest(manifest("v2 updated body"), overwriteRequest());
        assertThat(second.isSuccess())
                .as("re-import failed (would happen if it tried a duplicate INSERT): %s",
                        second.getErrorMessage())
                .isTrue();

        assertThat(templateRowCount())
                .as("still exactly one row after re-import (UNIQUE tenant_id+code upsert)")
                .isEqualTo(1L);
        assertThat(notificationTemplateService.getByCode(CODE).getBodyTemplate())
                .as("body updated by the upsert")
                .isEqualTo("v2 updated body");
    }

    @Test
    @DisplayName("preview reports a NOTIFICATION_TEMPLATE change for the manifest")
    void preview_reportsNotificationTemplateChange() {
        ImportPreviewResult preview = pluginImportService.previewFromManifest(manifest("preview body"));
        assertThat(preview.isValid())
                .as("preview must be valid, errors: %s", preview.getErrors()).isTrue();
        assertThat(preview.getChanges().getOrDefault(ResourceType.NOTIFICATION_TEMPLATE.name(), List.of()))
                .as("preview must report the NOTIFICATION_TEMPLATE change")
                .anyMatch(c -> CODE.equals(c.getResourceCode()));
    }
}
