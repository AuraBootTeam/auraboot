package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.NotificationTemplateDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end real-engine integration test closing the F2 loop: an imported notification template
 * makes a BPMN {@code notificationServiceTaskDelegate} step <em>deliver</em> instead of logging
 * "template not found, skipping".
 *
 * <p>Chain proven with a real SmartEngine + real PostgreSQL (no mocks):
 * <ol>
 *   <li>Ship the template through the F2 plugin-import resource type
 *       ({@code executeFromManifest} with {@code notificationTemplates}).</li>
 *   <li>Deploy a BPMN whose serviceTask is a {@code notificationServiceTaskDelegate} with
 *       {@code recipientType=role} (Wall&nbsp;1 fan-out) targeting the seeded test role.</li>
 *   <li>Start the process; assert an in-app {@code ab_notification} row is created for the role's
 *       member — i.e. the template was found, rendered and delivered.</li>
 * </ol>
 *
 * <p>Mirrors the iot {@code iot-alarm-handling} {@code notify_operator} step (recipientType=role
 * {@code iot_operator}, eventCode {@code iot_alarm_notify}); the test role code seeded by
 * {@link BaseIntegrationTest} ({@code test_user}) stands in for {@code iot_operator}.
 */
@DisplayName("Imported notification template makes a BPMN notify step deliver, not skip (F2, real SmartEngine)")
class AlarmNotifyDeliveryRealEngineIntegrationTest extends BaseIntegrationTest {

    private static final String TEMPLATE_CODE = "it_f2_notify_delivery";

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private NotificationMapper notificationMapper;

    /** start → notify (notificationServiceTaskDelegate, recipientType=role) → end. */
    private static final String BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:smart="http://smartengine.org/schema/process"
                              targetNamespace="http://auraboot.com/bpm"
                              id="%1$s-defs">
              <bpmn:process id="%1$s" name="F2 notify delivery" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="notify"/>
                <bpmn:serviceTask id="notify" name="Notify role"
                                  smart:class="notificationServiceTaskDelegate"
                                  smart:eventCode="%2$s"
                                  smart:recipientType="role"
                                  smart:recipient="%3$s"
                                  smart:templateParamsVars="alarmEventPid,severity">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="notify" targetRef="end"/>
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private void importTemplate() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("it.f2.notify-delivery");
        manifest.setNamespace("itf2nd");
        manifest.setVersion("1.0.0");
        manifest.setNotificationTemplates(List.of(
                NotificationTemplateDefinitionDTO.builder()
                        .code(TEMPLATE_CODE)
                        .name("F2 notify delivery")
                        .channel("in_app")
                        .category("system")
                        .subjectTemplate("Alarm (${severity})")
                        .bodyTemplate("Alarm ${alarmEventPid} raised with severity ${severity}.")
                        .enabled(true)
                        .build()));
        ImportExecuteResult result = pluginImportService.executeFromManifest(manifest,
                ImportRequest.builder().conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).build());
        assertThat(result.isSuccess()).as("template import failed: %s", result.getErrorMessage()).isTrue();
    }

    @Test
    @DisplayName("with the template imported, the role-fan-out notify step delivers an in-app notification")
    void importedTemplate_notifyStepDeliversInApp() {
        importTemplate();

        Long tenantId = MetaContext.getCurrentTenantId();
        Long memberUserId = getTestUser().getId();
        String roleCode = getTestRole().getCode();
        long before = notificationMapper.countByUser(tenantId, memberUserId);

        String processKey = "it-f2-notify-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest request =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "F2 notify delivery", "F2 IT",
                        "test", String.format(BPMN_TEMPLATE, processKey, TEMPLATE_CODE, roleCode),
                        null, null, null);
        BpmProcessDefinition def = deploymentService.create(request);
        deploymentService.deploy(def.getPid());

        Map<String, Object> startVars = new HashMap<>();
        startVars.put("alarmEventPid", "alarm-9001");
        startVars.put("severity", "GB_CRITICAL");
        ProcessInstance instance = processEngineService.startProcess(processKey, "biz-" + System.nanoTime(), startVars);
        assertThat(instance).as("process instance created").isNotNull();

        // The notify serviceTask resolved the imported template, fanned out to the role member,
        // and delivered an in-app notification (no "template not found, skipping").
        long after = notificationMapper.countByUser(tenantId, memberUserId);
        assertThat(after)
                .as("an in-app notification was delivered to the role member (before=%s, after=%s)", before, after)
                .isGreaterThan(before);

        List<Notification> notifications = notificationMapper.findByUser(tenantId, memberUserId, 20, 0);
        assertThat(notifications)
                .as("the delivered notification renders the template body with the process variables")
                .anyMatch(n -> n.getContent() != null
                        && n.getContent().contains("alarm-9001")
                        && n.getContent().contains("GB_CRITICAL"));
    }
}
