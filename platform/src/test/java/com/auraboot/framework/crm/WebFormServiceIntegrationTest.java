package com.auraboot.framework.crm;

import com.auraboot.framework.crm.dto.InboundChannelCreateRequest;
import com.auraboot.framework.crm.dto.InboundFormCreateRequest;
import com.auraboot.framework.crm.entity.InboundChannel;
import com.auraboot.framework.crm.entity.InboundForm;
import com.auraboot.framework.crm.service.InboundChannelService;
import com.auraboot.framework.crm.service.InboundFormService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for InboundFormService CRUD and public schema access.
 *
 * @since 5.3.0
 */
@Slf4j
@DisplayName("Web Form Service Integration Tests (WF-01~WF-03)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebFormServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InboundFormService inboundFormService;

    @Autowired
    private InboundChannelService inboundChannelService;

    private final String runId = "wf-" + System.currentTimeMillis();

    // Cross-test state
    private String channelPid;
    private String formPid;

    @BeforeAll
    void setupChannel() {
        // BaseIntegrationTest.setupTenantContext() runs @BeforeEach — we call it manually here
        // because @BeforeAll runs once and @BeforeEach is per-test.
        // The MetaContext is set by BaseIntegrationTest.setupTenantContext() before each test,
        // so we create the channel inside the first test instead.
    }

    // ==================== WF-01 ====================

    @Test
    @Order(1)
    @DisplayName("WF-01: create form persists with PID and channel association")
    void wf01_createForm() {
        // First create a WEB_FORM channel
        InboundChannelCreateRequest channelReq = new InboundChannelCreateRequest();
        channelReq.setName(runId + "-web-form-channel");
        channelReq.setChannelType("web_form");
        channelReq.setRateLimit(100);
        InboundChannel channel = inboundChannelService.create(channelReq);
        channelPid = channel.getPid();

        assertThat(channelPid).isNotNull().isNotBlank();
        log.info("WF-01: created WEB_FORM channel pid={}", channelPid);

        // Now create a form tied to this channel
        InboundFormCreateRequest req = new InboundFormCreateRequest();
        req.setName(runId + "-contact-form");
        req.setChannelPid(channelPid);
        req.setDescription("Integration test contact form");
        req.setFormSchema(List.of(
                Map.of("name", "email", "label", "Email", "type", "email", "required", true),
                Map.of("name", "name", "label", "Full Name", "type", "text", "required", true),
                Map.of("name", "phone", "label", "Phone", "type", "phone", "required", false),
                Map.of("name", "message", "label", "Message", "type", "textarea", "required", false)
        ));
        req.setStyleConfig(Map.of("primaryColor", "#3b82f6", "borderRadius", "8px"));
        req.setSuccessMessage("Thank you for contacting us!");
        req.setCorsOrigins(List.of("https://example.com", "https://www.example.com"));

        InboundForm result = inboundFormService.create(req);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotNull().isNotBlank();
        assertThat(result.getName()).isEqualTo(runId + "-contact-form");
        assertThat(result.getChannelPid()).isEqualTo(channelPid);
        assertThat(result.getDescription()).isEqualTo("Integration test contact form");
        assertThat(result.getFormSchema()).isNotNull();
        assertThat(result.getStyleConfig()).isNotNull();
        assertThat(result.getSuccessMessage()).isEqualTo("Thank you for contacting us!");
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getDeletedFlag()).isFalse();

        formPid = result.getPid();
        log.info("WF-01: created form pid={}, channelPid={}", formPid, channelPid);
    }

    // ==================== WF-02 ====================

    @Test
    @Order(2)
    @DisplayName("WF-02: getPublicSchema returns form fields and style config")
    void wf02_getPublicSchema() {
        assertThat(formPid).as("formPid must be set by WF-01").isNotNull();

        Map<String, Object> schema = inboundFormService.getPublicSchema(formPid);

        assertThat(schema).isNotNull();
        assertThat(schema.get("formPid")).isEqualTo(formPid);
        assertThat(schema.get("name")).isEqualTo(runId + "-contact-form");
        assertThat(schema.get("channelPid")).isEqualTo(channelPid);

        // formSchema should be a non-null list of field definitions
        Object formSchemaObj = schema.get("formSchema");
        assertThat(formSchemaObj).isNotNull();
        assertThat(formSchemaObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> formSchemaList = (List<Object>) formSchemaObj;
        assertThat(formSchemaList).hasSize(4);

        // styleConfig should be a non-null map
        Object styleConfigObj = schema.get("styleConfig");
        assertThat(styleConfigObj).isNotNull();
        assertThat(styleConfigObj).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> styleMap = (Map<String, Object>) styleConfigObj;
        assertThat(styleMap).containsKey("primaryColor");

        assertThat(schema.get("successMessage")).isEqualTo("Thank you for contacting us!");

        log.info("WF-02: public schema fields={}, style={}", formSchemaList.size(), styleMap);
    }

    // ==================== WF-03 ====================

    @Test
    @Order(3)
    @DisplayName("WF-03: listByChannel returns all non-deleted forms for the channel")
    void wf03_listByChannel() {
        assertThat(channelPid).as("channelPid must be set by WF-01").isNotNull();

        // Create a second form for the same channel
        InboundFormCreateRequest req2 = new InboundFormCreateRequest();
        req2.setName(runId + "-newsletter-form");
        req2.setChannelPid(channelPid);
        req2.setFormSchema(List.of(
                Map.of("name", "email", "label", "Email", "type", "email", "required", true)
        ));
        InboundForm form2 = inboundFormService.create(req2);
        assertThat(form2).isNotNull();
        assertThat(form2.getPid()).isNotNull();

        List<InboundForm> forms = inboundFormService.listByChannel(channelPid);

        assertThat(forms).isNotNull();
        assertThat(forms).hasSizeGreaterThanOrEqualTo(2);
        assertThat(forms).extracting(InboundForm::getPid)
                .contains(formPid, form2.getPid());
        // All forms should belong to the same channel
        assertThat(forms).allMatch(f -> channelPid.equals(f.getChannelPid()));
        // All forms should be non-deleted
        assertThat(forms).allMatch(f -> Boolean.FALSE.equals(f.getDeletedFlag()));

        log.info("WF-03: listByChannel returned {} forms for channelPid={}", forms.size(), channelPid);
    }
}
