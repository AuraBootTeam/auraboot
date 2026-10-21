package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.auraboot.framework.notification.service.NotificationSseService;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor.*;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import com.auraboot.framework.plugin.extension.integration.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginNotificationDeliveryAccessorTest {
    private final NotificationMapper mapper = mock(NotificationMapper.class);
    private final NotificationSseService sse = mock(NotificationSseService.class);
    private final ReliableIntegrationAccessor reliable = mock(ReliableIntegrationAccessor.class);
    private final NotificationDeliveryPlanResolver resolver = mock(NotificationDeliveryPlanResolver.class);
    private final StrictInAppDeliveryWriter writer = new StrictInAppDeliveryWriter(mapper, sse);
    private final PluginNotificationDeliveryAccessorImpl host = new PluginNotificationDeliveryAccessorImpl(List.of(resolver), reliable, writer);
    private final Request request = new Request(42, "trusted-fact-reference", Channel.IN_APP);
    private final Plan plan = new Plan(42, "trusted-fact-reference", "user-pid", "config-v1", "procurement-reminder",
            "reminder-pid", Instant.parse("2026-10-13T00:00:00Z"), "Title", "Content");

    @BeforeEach void setup() {
        MetaContext.setContext(42L, 12L, "actor", "actor");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        when(resolver.resolve(42, request.resolutionReference())).thenReturn(Optional.of(plan));
        when(mapper.findActiveRecipient(42, "user-pid")).thenReturn(99L);
    }
    @AfterEach void cleanup() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }
    private void enable() { ReflectionTestUtils.setField(host, "enabled", true); }
    private IntegrationEventEnvelope queued() {
        enable(); when(reliable.enqueueIdempotent(any())).thenReturn(true);
        assertThat(host.enqueue(request).status()).isEqualTo(Status.QUEUED);
        ArgumentCaptor<IntegrationEventEnvelope> event = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(reliable).enqueueIdempotent(event.capture()); return event.getValue();
    }
    @Test void defaultAndOlderHostsAreDisabledWithNoLookupsOrWrites() {
        assertThat(host.enqueue(request).reason()).isEqualTo(Reason.DISABLED);
        assertThat(new NotificationDeliveryAccessor() {}.enqueue(request).reason()).isEqualTo(Reason.DISABLED);
        verifyNoInteractions(resolver, reliable, mapper, sse);
    }
    @Test void unknownProvenanceAndMissingConfigRemainBlockedWithoutEnqueue() {
        enable(); when(resolver.resolve(anyLong(), anyString())).thenReturn(Optional.empty());
        assertThat(host.enqueue(request).reason()).isEqualTo(Reason.RESOLUTION_UNAVAILABLE);
        when(resolver.resolve(anyLong(), anyString())).thenReturn(Optional.of(new Plan(42, plan.reference(), "user-pid", "",
                plan.sourceType(), plan.sourceId(), plan.occurredAt(), plan.title(), plan.content())));
        assertThat(host.enqueue(request).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        verifyNoInteractions(reliable, mapper, sse);
    }
    @Test void emptyRecipientAndForeignResolverPlanCannotWrite() {
        enable();
        when(resolver.resolve(anyLong(), anyString())).thenReturn(Optional.of(new Plan(42, plan.reference(), "", "v1",
                plan.sourceType(), plan.sourceId(), plan.occurredAt(), plan.title(), plan.content())));
        assertThat(host.enqueue(request).status()).isEqualTo(Status.BLOCKED);
        when(resolver.resolve(anyLong(), anyString())).thenReturn(Optional.of(new Plan(43, plan.reference(), "user-pid", "v1",
                plan.sourceType(), plan.sourceId(), plan.occurredAt(), plan.title(), plan.content())));
        assertThat(host.enqueue(request).status()).isEqualTo(Status.BLOCKED);
        verifyNoInteractions(reliable, mapper, sse);
    }
    @Test void foreignRequestAndWechatWithLoginIdentityAreBlockedBeforeResolution() {
        enable();
        assertThat(host.enqueue(new Request(43, request.resolutionReference(), Channel.IN_APP)).reason()).isEqualTo(Reason.TENANT_MISMATCH);
        assertThat(host.enqueue(new Request(42, request.resolutionReference(), Channel.WECHAT_SUBSCRIPTION)).reason()).isEqualTo(Reason.WECHAT_NOT_CONFIGURED);
        verifyNoInteractions(resolver, reliable, mapper, sse);
    }
    @Test void duplicateResolverOwnershipIsBlocked() {
        var ambiguous = new PluginNotificationDeliveryAccessorImpl(List.of(resolver, resolver), reliable, writer);
        ReflectionTestUtils.setField(ambiguous, "enabled", true);
        assertThat(ambiguous.enqueue(request).reason()).isEqualTo(Reason.RESOLUTION_UNAVAILABLE);
        verifyNoInteractions(reliable, mapper, sse);
    }
    @Test void pluginClassloaderCannotSupplyItsOwnRecipientPolicy() throws Exception {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        try (var childLoader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
            var pluginResolver = (NotificationDeliveryPlanResolver) java.lang.reflect.Proxy.newProxyInstance(childLoader,
                    new Class<?>[]{NotificationDeliveryPlanResolver.class}, (proxy, method, args) -> {
                        called.set(true); return Optional.of(plan);
                    });
            var foreignHost = new PluginNotificationDeliveryAccessorImpl(List.of(pluginResolver), reliable, writer);
            ReflectionTestUtils.setField(foreignHost, "enabled", true);
            assertThat(foreignHost.enqueue(request).reason()).isEqualTo(Reason.RESOLUTION_UNAVAILABLE);
            assertThat(called).isFalse(); verifyNoInteractions(reliable, mapper, sse);
        }
    }
    @Test void inactiveOrNonmemberRecipientCannotEnqueue() {
        enable(); when(mapper.findActiveRecipient(42, "user-pid")).thenReturn(null);
        assertThatThrownBy(() -> host.enqueue(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        verifyNoInteractions(reliable, sse); verify(mapper, never()).insert(any(Notification.class));
    }
    @Test void queueResultDoesNotClaimPersistedAndExactReplayIsDistinct() {
        queued(); verify(mapper, never()).insert(any(Notification.class)); verifyNoInteractions(sse);
        when(reliable.enqueueIdempotent(any())).thenReturn(false);
        assertThat(host.enqueue(request).status()).isEqualTo(Status.REPLAYED);
    }
    @Test void forgedEventPayloadAndRemovedMembershipCannotPersist() {
        var event = queued(); var payload = new HashMap<>(event.payload()); payload.put("recipientUserPid", "foreign-user");
        var forged = new IntegrationEventEnvelope(event.schemaVersion(), event.eventId(), event.eventType(), event.source(),
                event.subject(), event.occurredAt(), event.tenantId(), event.correlationId(), event.causationId(),
                event.orderingKey(), event.sequence(), payload, event.headers());
        assertThatThrownBy(() -> host.consume(forged)).hasMessage("NOTIFICATION_ENVELOPE_CONFLICT");
        when(mapper.findActiveRecipient(42, "user-pid")).thenReturn(null);
        assertThatThrownBy(() -> host.consume(event)).hasMessage("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        verify(mapper, never()).insert(any(Notification.class)); verifyNoInteractions(sse);
    }
    @Test void persistenceFailsClosedAndDoesNotScheduleSse() {
        var event = queued(); when(mapper.insert(any(Notification.class))).thenReturn(0);
        assertThatThrownBy(() -> host.consume(event)).hasMessage("NOTIFICATION_PERSISTENCE_FAILED");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty(); verifyNoInteractions(sse);
    }
    @Test void persistedRowHasExactTenantReceiverContentAndSseOnlyAfterCommit() {
        var event = queued();
        when(mapper.insert(any(Notification.class))).thenAnswer(invocation -> {
            Notification row = invocation.getArgument(0); row.setId(101L); return 1;
        });
        host.consume(event);
        ArgumentCaptor<Notification> capture = ArgumentCaptor.forClass(Notification.class);
        verify(mapper).insert(capture.capture());
        Notification row = capture.getValue();
        assertThat(row.getTenantId()).isEqualTo(42L); assertThat(row.getUserId()).isEqualTo(99L);
        assertThat(row.getTitle()).isEqualTo("Title"); assertThat(row.getContent()).isEqualTo("Content");
        assertThat(row.getSourceId()).isEqualTo("reminder-pid"); assertThat(row.getIsRead()).isFalse();
        verifyNoInteractions(sse);
        when(sse.getActiveConnectionCount(99L)).thenReturn(1); when(mapper.countUnread(42L, 99L)).thenReturn(3);
        MetaContext.setContext(43L, 3L, "other", "other");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(sse).pushUnreadCount(99L, 3); assertThat(MetaContext.getCurrentTenantId()).isEqualTo(43L);
    }
    @Test void insertExceptionPropagatesAndSseFailureDoesNotRewritePersistence() {
        var event = queued(); when(mapper.insert(any(Notification.class))).thenThrow(new IllegalStateException("synthetic insert failure"));
        assertThatThrownBy(() -> host.consume(event)).hasMessage("synthetic insert failure");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        when(mapper.insert(any(Notification.class))).thenAnswer(invocation -> {
            Notification row = invocation.getArgument(0); row.setId(101L); return 1;
        });
        host.consume(event); when(sse.getActiveConnectionCount(99L)).thenThrow(new IllegalStateException("synthetic SSE failure"));
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit())).doesNotThrowAnyException();
        verify(mapper, times(2)).insert(any(Notification.class));
    }
    @Test void missingTransactionCannotPersistOrEnqueue() {
        enable(); TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> host.enqueue(request)).hasMessage("NOTIFICATION_TRANSACTION_REQUIRED");
        assertThatThrownBy(() -> writer.persist(plan)).hasMessage("NOTIFICATION_TRANSACTION_REQUIRED");
        verifyNoInteractions(mapper, reliable, sse);
    }
}
