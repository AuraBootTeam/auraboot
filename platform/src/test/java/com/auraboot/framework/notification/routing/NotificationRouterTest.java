package com.auraboot.framework.notification.routing;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.event.AuraEvent;
import com.auraboot.framework.notification.channel.NotificationChannel;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationRouter and DefaultRecipientResolver.
 *
 * @since 6.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationRouter")
class NotificationRouterTest {

    @Mock
    private NotificationTemplateService templateService;

    @Mock
    private NotificationChannel inAppChannel;

    @Mock
    private NotificationChannel emailChannel;

    @AfterEach
    void cleanup() {
        MetaContext.clear();
    }

    // ==================== Router Tests ====================

    @Nested
    @DisplayName("route()")
    class RouteTests {

        @Test
        @DisplayName("onCommandCompleted routes to IN_APP when template found")
        void routesToInAppWhenTemplateFound() {
            MetaContext.setContext(1L, 42L, "u-pid", "testuser");

            NotificationTemplate template = buildTemplate("createOrder", "in_app",
                    null, "operator", "business");
            template.setSubjectTemplate("Order ${orderId} created");
            template.setBodyTemplate("Your order ${orderId} has been placed");
            when(templateService.getByCode("createOrder")).thenReturn(template);

            when(inAppChannel.getChannelCode()).thenReturn("in_app");
            when(inAppChannel.isAvailable()).thenReturn(true);
            when(inAppChannel.send(any())).thenReturn(NotificationResult.ok());

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order",
                    Map.of("orderId", "ORD-123"),
                    "createOrder", "create");

            router.onCommandCompleted(event);

            ArgumentCaptor<NotificationMessage> captor =
                    ArgumentCaptor.forClass(NotificationMessage.class);
            verify(inAppChannel).send(captor.capture());

            NotificationMessage msg = captor.getValue();
            assertEquals(1L, msg.getTenantId());
            assertEquals(List.of(42L), msg.getRecipientUserIds());
            assertEquals("Order ORD-123 created", msg.getSubject());
            assertEquals("Your order ORD-123 has been placed", msg.getBody());
            assertEquals("business", msg.getCategory());
            assertEquals("order", msg.getSourceType());
            assertEquals("REC-001", msg.getSourceId());
        }

        @Test
        @DisplayName("skips when no template exists")
        void skipsWhenNoTemplate() {
            when(templateService.getByCode("unknownCmd")).thenReturn(null);

            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "unknownCmd", "create");

            router.onCommandCompleted(event);

            verify(inAppChannel, never()).send(any());
        }

        @Test
        @DisplayName("skips when template is disabled")
        void skipsWhenTemplateDisabled() {
            NotificationTemplate template = buildTemplate("createOrder", "in_app",
                    null, null, null);
            template.setEnabled(false);
            when(templateService.getByCode("createOrder")).thenReturn(template);

            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "createOrder", "create");

            router.onCommandCompleted(event);

            verify(inAppChannel, never()).send(any());
        }

        @Test
        @DisplayName("multi-channel dispatch: dispatches to both IN_APP and EMAIL")
        void multiChannelDispatch() {
            MetaContext.setContext(1L, 42L, "u-pid", "testuser");

            NotificationTemplate template = buildTemplate("approveOrder", "in_app",
                    "[\"IN_APP\", \"EMAIL\"]", "operator", "approval");
            template.setSubjectTemplate("Approved");
            template.setBodyTemplate("Order approved");
            when(templateService.getByCode("approveOrder")).thenReturn(template);

            when(inAppChannel.getChannelCode()).thenReturn("in_app");
            when(inAppChannel.isAvailable()).thenReturn(true);
            when(inAppChannel.send(any())).thenReturn(NotificationResult.ok());

            when(emailChannel.getChannelCode()).thenReturn("email");
            when(emailChannel.isAvailable()).thenReturn(true);
            when(emailChannel.send(any())).thenReturn(NotificationResult.ok());

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel, emailChannel));

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-002", "order", Map.of(),
                    "approveOrder", "update");

            router.onCommandCompleted(event);

            verify(inAppChannel).send(any());
            verify(emailChannel).send(any());

            ArgumentCaptor<NotificationMessage> captor =
                    ArgumentCaptor.forClass(NotificationMessage.class);
            verify(emailChannel).send(captor.capture());
            assertEquals("approval", captor.getValue().getCategory());
        }

        @Test
        @DisplayName("BpmEvent routes correctly")
        void bpmEventRoutes() {
            MetaContext.setContext(1L, 99L, "u-pid", "bpmuser");

            BpmEvent bpmEvent = BpmEvent.of(1L, "process_started", "bpm",
                    "leave_request", "inst-001", null,
                    Map.of("processKey", "leave_request"));

            String eventType = bpmEvent.getEventType(); // "bpm:process_started"

            NotificationTemplate template = buildTemplate(eventType, "in_app",
                    null, "operator", "system");
            template.setSubjectTemplate("Process started");
            template.setBodyTemplate("${processKey} started");
            when(templateService.getByCode(eventType)).thenReturn(template);

            when(inAppChannel.getChannelCode()).thenReturn("in_app");
            when(inAppChannel.isAvailable()).thenReturn(true);
            when(inAppChannel.send(any())).thenReturn(NotificationResult.ok());

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            router.onBpmEvent(bpmEvent);

            ArgumentCaptor<NotificationMessage> captor =
                    ArgumentCaptor.forClass(NotificationMessage.class);
            verify(inAppChannel).send(captor.capture());
            assertEquals("leave_request started", captor.getValue().getBody());
            assertEquals("system", captor.getValue().getCategory());
        }
    }

    // ==================== RecipientResolver Tests ====================

    @Nested
    @DisplayName("DefaultRecipientResolver")
    class RecipientResolverTests {

        private final DefaultRecipientResolver resolver = new DefaultRecipientResolver();

        @Test
        @DisplayName("OPERATOR strategy returns MetaContext user")
        void operatorReturnsCurrentUser() {
            MetaContext.setContext(1L, 55L, "u-pid", "operator");

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "test", "create");

            List<Long> result = resolver.resolve(event, "operator", null);
            assertEquals(List.of(55L), result);
        }

        @Test
        @DisplayName("RECORD_OWNER strategy extracts created_by from payload")
        void recordOwnerExtractsCreatedBy() {
            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order",
                    Map.of("created_by", 77L),
                    "test", "create");

            List<Long> result = resolver.resolve(event, "record_owner", null);
            assertEquals(List.of(77L), result);
        }

        @Test
        @DisplayName("RECORD_OWNER strategy handles string created_by")
        void recordOwnerHandlesStringCreatedBy() {
            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order",
                    Map.of("created_by", "88"),
                    "test", "create");

            List<Long> result = resolver.resolve(event, "record_owner", null);
            assertEquals(List.of(88L), result);
        }

        @Test
        @DisplayName("unknown strategy returns empty list")
        void unknownStrategyReturnsEmpty() {
            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "test", "create");

            List<Long> result = resolver.resolve(event, "custom_unknown", null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null strategy defaults to OPERATOR")
        void nullStrategyDefaultsToOperator() {
            MetaContext.setContext(1L, 100L, "u-pid", "user");

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "test", "create");

            List<Long> result = resolver.resolve(event, null, null);
            assertEquals(List.of(100L), result);
        }

        @Test
        @DisplayName("OPERATOR returns empty when MetaContext not set")
        void operatorReturnsEmptyWhenNoContext() {
            // MetaContext is not set — clear just to be sure
            MetaContext.clear();

            CommandCompletedEvent event = new CommandCompletedEvent(
                    1L, "REC-001", "order", Map.of(),
                    "test", "create");

            List<Long> result = resolver.resolve(event, "operator", null);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== Template Rendering Tests ====================

    @Nested
    @DisplayName("renderTemplate()")
    class RenderTemplateTests {

        @Test
        @DisplayName("template variable rendering works")
        void variableRenderingWorks() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            String result = router.renderTemplate(
                    "Hello ${name}, your order ${orderId} is ${status}",
                    Map.of("name", "Alice", "orderId", "ORD-999", "status", "confirmed"));

            assertEquals("Hello Alice, your order ORD-999 is confirmed", result);
        }

        @Test
        @DisplayName("renderTemplate handles null template gracefully")
        void handlesNullTemplate() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            assertEquals("", router.renderTemplate(null, Map.of()));
            assertEquals("", router.renderTemplate("", Map.of()));
        }

        @Test
        @DisplayName("renderTemplate replaces null variable values with empty string")
        void handlesNullVariableValues() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            // Use a mutable map since Map.of() doesn't allow null values
            java.util.HashMap<String, Object> vars = new java.util.HashMap<>();
            vars.put("name", null);
            String result = router.renderTemplate("Hello ${name}", vars);
            assertEquals("Hello ", result);
        }
    }

    // ==================== parseChannels Tests ====================

    @Nested
    @DisplayName("parseChannels()")
    class ParseChannelsTests {

        @Test
        @DisplayName("parses JSON array channels field")
        void parsesJsonArray() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            NotificationTemplate template = new NotificationTemplate();
            template.setChannels("[\"IN_APP\", \"EMAIL\"]");
            template.setChannel("in_app");

            List<String> result = router.parseChannels(template);
            assertEquals(List.of("in_app", "email"), result);
        }

        @Test
        @DisplayName("falls back to single channel when channels field is null")
        void fallsBackToSingleChannel() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            NotificationTemplate template = new NotificationTemplate();
            template.setChannels(null);
            template.setChannel("email");

            List<String> result = router.parseChannels(template);
            assertEquals(List.of("email"), result);
        }

        @Test
        @DisplayName("defaults to IN_APP when both channels and channel are null")
        void defaultsToInApp() {
            when(inAppChannel.getChannelCode()).thenReturn("in_app");

            NotificationRouter router = new NotificationRouter(
                    templateService,
                    new DefaultRecipientResolver(),
                    List.of(inAppChannel));

            NotificationTemplate template = new NotificationTemplate();
            template.setChannels(null);
            template.setChannel(null);

            List<String> result = router.parseChannels(template);
            assertEquals(List.of("in_app"), result);
        }
    }

    // ==================== Helpers ====================

    private NotificationTemplate buildTemplate(String code, String channel,
                                                String channels, String recipientStrategy,
                                                String category) {
        NotificationTemplate t = new NotificationTemplate();
        t.setId(1L);
        t.setTenantId(1L);
        t.setPid("tpl-001");
        t.setCode(code);
        t.setName("Test Template");
        t.setChannel(channel);
        t.setChannels(channels);
        t.setRecipientStrategy(recipientStrategy);
        t.setCategory(category);
        t.setEnabled(true);
        t.setSubjectTemplate("Subject");
        t.setBodyTemplate("Body");
        return t;
    }
}
