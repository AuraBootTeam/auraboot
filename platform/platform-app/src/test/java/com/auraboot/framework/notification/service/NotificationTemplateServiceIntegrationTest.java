package com.auraboot.framework.notification.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * NotificationTemplateService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>NT-01: create persists template with correct fields</li>
 *   <li>NT-02: getByCode returns the created template</li>
 *   <li>NT-03: listByChannel returns matching templates</li>
 *   <li>NT-04: listAll includes the created template</li>
 *   <li>NT-05: update changes name</li>
 *   <li>NT-06: renderPreview substitutes variables</li>
 *   <li>NT-07: delete removes the template</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationTemplateServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NotificationTemplateService templateService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private String templatePid;

    // ==================== NT-01: create ====================

    @Test
    @Order(1)
    @DisplayName("NT-01: create persists template with correct fields")
    void create_persistsTemplate() {
        NotificationTemplateCreateRequest req = buildRequest(
                "TPL-" + runId, "Test Template " + runId, "in_app",
                "Hello ${name}", "Welcome to ${app}!");

        NotificationTemplate saved = templateService.create(req);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPid()).isNotBlank();
        assertThat(saved.getCode()).isEqualTo("TPL-" + runId);
        assertThat(saved.getChannel()).isEqualTo("in_app");
        assertThat(saved.getEnabled()).isTrue();
        templatePid = saved.getPid();
        log.info("NT-01: created template pid={}", templatePid);
    }

    @Test
    @Order(2)
    @DisplayName("NT-02: getByCode returns the created template")
    void getByCode_returnsTemplate() {
        assertThat(templatePid).as("templatePid must be set by NT-01").isNotBlank();

        NotificationTemplate found = templateService.getByCode("TPL-" + runId);

        assertThat(found).isNotNull();
        assertThat(found.getPid()).isEqualTo(templatePid);
        assertThat(found.getName()).isEqualTo("Test Template " + runId);
    }

    @Test
    @Order(3)
    @DisplayName("NT-03: listByChannel returns matching templates")
    void listByChannel_returnsMatchingTemplates() {
        List<NotificationTemplate> inAppTemplates = templateService.listByChannel("in_app");

        assertThat(inAppTemplates).isNotNull();
        boolean found = inAppTemplates.stream()
                .anyMatch(t -> ("TPL-" + runId).equals(t.getCode()));
        assertThat(found).isTrue();
        inAppTemplates.forEach(t -> assertThat(t.getChannel()).isEqualTo("in_app"));
    }

    @Test
    @Order(4)
    @DisplayName("NT-04: listAll includes the created template")
    void listAll_includesCreatedTemplate() {
        List<NotificationTemplate> all = templateService.listAll();

        assertThat(all).isNotNull().isNotEmpty();
        boolean found = all.stream()
                .anyMatch(t -> ("TPL-" + runId).equals(t.getCode()));
        assertThat(found).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("NT-05: update changes name")
    void update_changesName() {
        assertThat(templatePid).as("templatePid must be set by NT-01").isNotBlank();

        NotificationTemplateCreateRequest updateReq = buildRequest(
                "TPL-" + runId, "Updated Template " + runId, "in_app",
                "Hi ${name}", "Updated body ${app}!");

        NotificationTemplate updated = templateService.update(templatePid, updateReq);

        assertThat(updated.getName()).isEqualTo("Updated Template " + runId);
    }

    @Test
    @Order(6)
    @DisplayName("NT-06: renderPreview substitutes variables")
    void renderPreview_substitutesVariables() {
        assertThat(templatePid).as("templatePid must be set by NT-01").isNotBlank();

        Map<String, Object> vars = Map.of("name", "Alice", "app", "AuraBoot");
        String preview = templateService.renderPreview("TPL-" + runId, vars);

        // Body after NT-05 update is "Updated body ${app}!" — renders "Updated body AuraBoot!"
        assertThat(preview).isNotBlank();
        assertThat(preview).contains("AuraBoot");
    }

    @Test
    @Order(7)
    @DisplayName("NT-07: delete removes the template")
    void delete_removesTemplate() {
        assertThat(templatePid).as("templatePid must be set by NT-01").isNotBlank();

        assertThatCode(() -> templateService.delete(templatePid))
                .doesNotThrowAnyException();

        NotificationTemplate deleted = templateService.getByCode("TPL-" + runId);
        assertThat(deleted).isNull();
    }

    // ==================== helper ====================

    private NotificationTemplateCreateRequest buildRequest(
            String code, String name, String channel, String subject, String body) {
        NotificationTemplateCreateRequest req = new NotificationTemplateCreateRequest();
        req.setCode(code);
        req.setName(name);
        req.setChannel(channel);
        req.setSubjectTemplate(subject);
        req.setBodyTemplate(body);
        req.setEnabled(true);
        return req;
    }
}
