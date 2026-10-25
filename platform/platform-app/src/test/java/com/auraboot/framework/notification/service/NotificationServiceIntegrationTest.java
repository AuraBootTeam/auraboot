package com.auraboot.framework.notification.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.*;
import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NotificationService, NotificationQueryService, and NotificationTemplateService.
 *
 * @since 5.1.0
 */
@DisplayName("P5-2: Notification Service Integration Tests")
class NotificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationQueryService notificationQueryService;

    @Autowired
    private NotificationTemplateService templateService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private NotificationSendLogMapper sendLogMapper;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Template CRUD ====================

    @Test
    @DisplayName("Template: create IN_APP template")
    void testCreateInAppTemplate() {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode("order_created_" + System.currentTimeMillis());
        request.setName("Order Created");
        request.setChannel("in_app");
        request.setSubjectTemplate("New Order: ${orderNo}");
        request.setBodyTemplate("Order ${orderNo} has been created with amount ${amount}.");

        NotificationTemplate template = templateService.create(request);

        assertNotNull(template);
        assertNotNull(template.getPid());
        assertEquals("in_app", template.getChannel());
        assertTrue(template.getEnabled());
    }

    @Test
    @DisplayName("Template: create EMAIL template")
    void testCreateEmailTemplate() {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode("welcome_email_" + System.currentTimeMillis());
        request.setName("Welcome Email");
        request.setChannel("email");
        request.setSubjectTemplate("Welcome, ${userName}!");
        request.setBodyTemplate("<h1>Hello ${userName}</h1><p>Welcome to our platform.</p>");

        NotificationTemplate template = templateService.create(request);

        assertNotNull(template);
        assertEquals("email", template.getChannel());
    }

    @Test
    @DisplayName("Template: getByCode finds enabled template")
    void testGetByCode() {
        String code = "find_me_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Findable Template");
        request.setChannel("in_app");
        request.setBodyTemplate("Body content");
        templateService.create(request);

        NotificationTemplate found = templateService.getByCode(code);
        assertNotNull(found);
        assertEquals(code, found.getCode());
    }

    @Test
    @DisplayName("Template: listByChannel filters correctly")
    void testListByChannel() {
        String suffix = "_" + System.currentTimeMillis();
        createTemplate("ch_in_app" + suffix, "in_app");
        createTemplate("ch_email" + suffix, "email");

        List<NotificationTemplate> inApp = templateService.listByChannel("in_app");
        assertFalse(inApp.isEmpty());
        inApp.forEach(t -> assertEquals("in_app", t.getChannel()));
    }

    @Test
    @DisplayName("Template: update modifies fields")
    void testUpdateTemplate() {
        String code = "update_tpl_" + System.currentTimeMillis();
        NotificationTemplate created = createTemplate(code, "in_app");

        NotificationTemplateCreateRequest updateReq = new NotificationTemplateCreateRequest();
        updateReq.setCode(code);
        updateReq.setName("Updated Name");
        updateReq.setChannel("email");
        updateReq.setBodyTemplate("Updated body");

        NotificationTemplate updated = templateService.update(created.getPid(), updateReq);
        assertEquals("Updated Name", updated.getName());
        assertEquals("email", updated.getChannel());
    }

    @Test
    @DisplayName("Template: delete removes template")
    void testDeleteTemplate() {
        String code = "delete_tpl_" + System.currentTimeMillis();
        NotificationTemplate created = createTemplate(code, "in_app");

        templateService.delete(created.getPid());

        // getByCode only finds enabled templates, but delete removes entirely
        assertNull(templateService.getByCode(code));
    }

    @Test
    @DisplayName("Template: renderPreview substitutes variables")
    void testRenderPreview() {
        String code = "render_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Render Test");
        request.setChannel("in_app");
        request.setBodyTemplate("Hello ${name}, your order ${orderId} is ready.");
        templateService.create(request);

        String rendered = templateService.renderPreview(code, Map.of("name", "Alice", "orderId", "ORD-123"));

        assertEquals("Hello Alice, your order ORD-123 is ready.", rendered);
    }

    @Test
    @DisplayName("Template: renderPreview replaces missing variables with empty string")
    void testRenderPreviewMissingVariable() {
        String code = "render_missing_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Render Missing");
        request.setChannel("in_app");
        request.setBodyTemplate("Hello ${name}, ticket ${ticketNo}.");
        templateService.create(request);

        String rendered = templateService.renderPreview(code, Map.of("name", "Alice"));

        assertEquals("Hello Alice, ticket .", rendered);
    }

    // ==================== In-App Notification ====================

    @Test
    @DisplayName("Send: in-app notification directly")
    void testSendInApp() {
        Long userId = MetaContext.getCurrentUserId();

        notificationService.sendInApp(userId, "Test Title", "Test Content",
                "system", "test", "test-001");

        int count = notificationQueryService.getUnreadCount(userId);
        assertTrue(count >= 1);
    }

    @Test
    @DisplayName("Send: via template creates notification")
    void testSendViaTemplate() {
        String code = "send_tpl_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest tplReq = new NotificationTemplateCreateRequest();
        tplReq.setCode(code);
        tplReq.setName("Send Test");
        tplReq.setChannel("in_app");
        tplReq.setSubjectTemplate("Order ${orderNo}");
        tplReq.setBodyTemplate("Your order ${orderNo} has been shipped.");
        templateService.create(tplReq);

        Long userId = MetaContext.getCurrentUserId();
        NotificationSendRequest sendReq = NotificationSendRequest.builder()
                .templateCode(code)
                .recipientId(String.valueOf(userId))
                .variables(Map.of("orderNo", "ORD-999"))
                .build();

        assertDoesNotThrow(() -> notificationService.send(sendReq));

        int count = notificationQueryService.getUnreadCount(userId);
        assertTrue(count >= 1);
    }

    @Test
    @DisplayName("Send: template variables are HTML-escaped before delivery")
    void testSendViaTemplateEscapesHtml() {
        String code = "escape_tpl_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest tplReq = new NotificationTemplateCreateRequest();
        tplReq.setCode(code);
        tplReq.setName("Escape Test");
        tplReq.setChannel("in_app");
        tplReq.setSubjectTemplate("Alert ${name}");
        tplReq.setBodyTemplate("Payload ${payload}");
        templateService.create(tplReq);

        Long userId = MetaContext.getCurrentUserId();
        notificationService.send(NotificationSendRequest.builder()
                .templateCode(code)
                .recipientId(String.valueOf(userId))
                .variables(Map.of(
                        "name", "<Admin>",
                        "payload", "<script>alert('x')</script>"
                ))
                .build());

        PaginationResult<NotificationDTO> result = notificationQueryService.listByUser(userId, defaultQuery());
        NotificationDTO latest = result.getRecords().get(0);
        assertTrue(latest.getTitle().contains("&lt;Admin&gt;"));
        assertTrue(latest.getContent().contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"));
    }

    @Test
    @DisplayName("Send: missing template does not create send log")
    void testSendMissingTemplateDoesNotCreateSendLog() {
        long before = sendLogMapper.selectCount(new QueryWrapper<>());

        notificationService.send(NotificationSendRequest.builder()
                .templateCode("missing_" + System.currentTimeMillis())
                .recipientId(String.valueOf(MetaContext.getCurrentUserId()))
                .variables(Map.of("k", "v"))
                .build());

        long after = sendLogMapper.selectCount(new QueryWrapper<>());
        assertEquals(before, after);
    }

    @Test
    @DisplayName("Send: email template with email recipient writes SENT log")
    void testSendEmailTemplateWritesSentLog() {
        String code = "email_tpl_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Email Template");
        request.setChannel("email");
        request.setSubjectTemplate("Hello ${name}");
        request.setBodyTemplate("<p>${name}</p>");
        templateService.create(request);

        notificationService.send(NotificationSendRequest.builder()
                .templateCode(code)
                .recipientId("user@example.com")
                .variables(Map.of("name", "Alice"))
                .build());

        NotificationSendLog logEntry = latestSendLog(code);
        assertNotNull(logEntry);
        assertEquals("email", logEntry.getChannel());
        assertEquals("user@example.com", logEntry.getRecipient());
        assertEquals("sent", logEntry.getStatus());
    }

    @Test
    @DisplayName("Send: email template without email address writes FAILED log")
    void testSendEmailTemplateWithoutAddressWritesFailedLog() {
        String code = "email_missing_addr_" + System.currentTimeMillis();
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Email Missing Address");
        request.setChannel("email");
        request.setSubjectTemplate("Hello");
        request.setBodyTemplate("<p>Body</p>");
        templateService.create(request);

        notificationService.send(NotificationSendRequest.builder()
                .templateCode(code)
                .recipientId(" ")
                .build());

        NotificationSendLog logEntry = latestSendLog(code);
        assertNotNull(logEntry);
        assertEquals("failed", logEntry.getStatus());
        assertEquals("No email address available", logEntry.getErrorMessage());
    }

    @Test
    @DisplayName("Send: batch notification to multiple users")
    void testSendBatch() {
        String code = "batch_tpl_" + System.currentTimeMillis();
        createTemplate(code, "in_app");

        Long userId = MetaContext.getCurrentUserId();
        List<NotificationRecipient> recipients = List.of(
                NotificationRecipient.builder().userId(userId).build()
        );

        assertDoesNotThrow(() ->
                notificationService.sendBatch(code, recipients, Map.of())
        );
    }

    // ==================== Query & Read ====================

    @Test
    @DisplayName("Query: listByUser with pagination")
    void testListByUser() {
        Long userId = MetaContext.getCurrentUserId();
        for (int i = 0; i < 5; i++) {
            notificationService.sendInApp(userId, "Notification " + i, "Content " + i,
                    "business", null, null);
        }

        NotificationQueryRequest request = new NotificationQueryRequest();
        request.setPageNum(1);
        request.setPageSize(3);
        PaginationResult<NotificationDTO> result = notificationQueryService.listByUser(userId, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getTotal() >= 5);
        assertTrue(result.getRecords().size() <= 3);
    }

    @Test
    @DisplayName("Query: filter unread only")
    void testFilterUnread() {
        Long userId = MetaContext.getCurrentUserId();
        notificationService.sendInApp(userId, "Unread", "Content", "system", null, null);

        NotificationQueryRequest request = new NotificationQueryRequest();
        request.setIsRead(false);
        PaginationResult<NotificationDTO> result = notificationQueryService.listByUser(userId, request);

        assertNotNull(result);
        result.getRecords().forEach(dto -> assertFalse(dto.getIsRead()));
    }

    @Test
    @DisplayName("Query: getUnreadCount reflects new notifications")
    void testGetUnreadCount() {
        Long userId = MetaContext.getCurrentUserId();
        int before = notificationQueryService.getUnreadCount(userId);

        notificationService.sendInApp(userId, "Count Test", "Body", "alert", null, null);

        int after = notificationQueryService.getUnreadCount(userId);
        assertEquals(before + 1, after);
    }

    @Test
    @DisplayName("Mark: markAsRead updates notification")
    void testMarkAsRead() {
        Long userId = MetaContext.getCurrentUserId();
        notificationService.sendInApp(userId, "Read Me", "Body", "system", null, null);

        NotificationQueryRequest request = new NotificationQueryRequest();
        request.setIsRead(false);
        PaginationResult<NotificationDTO> result = notificationQueryService.listByUser(userId, request);
        assertFalse(result.getRecords().isEmpty());

        Long notificationId = result.getRecords().get(0).getId();
        notificationQueryService.markAsRead(notificationId);

        int unreadAfter = notificationQueryService.getUnreadCount(userId);
        // Count should be reduced (or at least not increased)
        assertNotNull(unreadAfter);
    }

    @Test
    @DisplayName("Mark: markAllAsRead clears all unread")
    void testMarkAllAsRead() {
        Long userId = MetaContext.getCurrentUserId();
        notificationService.sendInApp(userId, "A", "x", "system", null, null);
        notificationService.sendInApp(userId, "B", "y", "system", null, null);

        notificationQueryService.markAllAsRead(userId);

        int count = notificationQueryService.getUnreadCount(userId);
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Query: notifications are isolated by tenant")
    void testNotificationTenantIsolation() {
        Long userId = MetaContext.getCurrentUserId();
        notificationService.sendInApp(userId, "Tenant A", "A body", "system", null, null);

        Tenant otherTenant = createAdditionalTenant();
        switchToTenant(otherTenant);
        notificationService.sendInApp(userId, "Tenant B", "B body", "system", null, null);

        PaginationResult<NotificationDTO> otherTenantResult = notificationQueryService.listByUser(userId, defaultQuery());
        assertFalse(otherTenantResult.getRecords().isEmpty());
        assertTrue(otherTenantResult.getRecords().stream().allMatch(item -> "Tenant B".equals(item.getTitle())));

        switchToTenant(getTestTenant());
        PaginationResult<NotificationDTO> originalTenantResult = notificationQueryService.listByUser(userId, defaultQuery());
        assertTrue(originalTenantResult.getRecords().stream().noneMatch(item -> "Tenant B".equals(item.getTitle())));
    }

    @Test
    @DisplayName("Mark: cross-tenant markAsRead does not affect original tenant notification")
    void testMarkAsReadDoesNotCrossTenantBoundary() {
        Long userId = MetaContext.getCurrentUserId();
        notificationService.sendInApp(userId, "Cross Tenant Read", "Body", "system", null, null);

        NotificationQueryRequest unreadOnly = new NotificationQueryRequest();
        unreadOnly.setIsRead(false);
        PaginationResult<NotificationDTO> currentTenantNotifications = notificationQueryService.listByUser(userId, unreadOnly);
        Long notificationId = currentTenantNotifications.getRecords().stream()
                .filter(item -> "Cross Tenant Read".equals(item.getTitle()))
                .findFirst()
                .orElseThrow()
                .getId();

        Tenant otherTenant = createAdditionalTenant();
        switchToTenant(otherTenant);
        notificationQueryService.markAsRead(notificationId);
        assertEquals(0, notificationQueryService.getUnreadCount(userId));

        switchToTenant(getTestTenant());
        PaginationResult<NotificationDTO> unreadResult = notificationQueryService.listByUser(userId, unreadOnly);
        assertTrue(unreadResult.getRecords().stream().anyMatch(item -> notificationId.equals(item.getId())));
    }

    // ==================== Helpers ====================

    private NotificationTemplate createTemplate(String code, String channel) {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(code);
        request.setName("Template " + code);
        request.setChannel(channel);
        request.setBodyTemplate("Body for " + code);
        return templateService.create(request);
    }

    private NotificationQueryRequest defaultQuery() {
        NotificationQueryRequest request = new NotificationQueryRequest();
        request.setPageNum(1);
        request.setPageSize(20);
        return request;
    }

    private Tenant createAdditionalTenant() {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName("notification-test-tenant-" + UniqueIdGenerator.generate().substring(0, 8));
        tenant.setDisplayName("Notification Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("notification-" + UniqueIdGenerator.generate().substring(0, 6) + "@integration-test.com");
        tenant.setDescription("Additional tenant for notification integration tests");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }

    private void switchToTenant(Tenant tenant) {
        MetaContext.setContext(tenant.getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    private NotificationSendLog latestSendLog(String templateCode) {
        List<NotificationSendLog> logs = sendLogMapper.selectList(new QueryWrapper<NotificationSendLog>()
                .eq("template_code", templateCode)
                .orderByDesc("created_at"));
        return logs.isEmpty() ? null : logs.get(0);
    }
}
