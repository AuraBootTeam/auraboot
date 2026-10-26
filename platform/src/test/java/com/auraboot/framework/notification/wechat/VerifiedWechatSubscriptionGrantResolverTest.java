package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.notification.mapper.*;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic host-policy and reservation contracts; database ownership/concurrency require real PostgreSQL. */
class VerifiedWechatSubscriptionGrantResolverTest {
    static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    final NotificationDeliveryPlanResolver plans = mock(NotificationDeliveryPlanResolver.class);
    final NotificationMapper recipients = mock(NotificationMapper.class);
    final AuthIdentityMapper identities = mock(AuthIdentityMapper.class);
    final WechatSubscriptionConsentMapper consent = mock(WechatSubscriptionConsentMapper.class);
    final WechatSubscriptionReservationMapper reservations = mock(WechatSubscriptionReservationMapper.class);
    final WechatSubscriptionReservationProperties policy = new WechatSubscriptionReservationProperties();
    final WechatSubscriptionProperties delivery = new WechatSubscriptionProperties();
    final WechatMiniProperties mini = new WechatMiniProperties();
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    NotificationDeliveryPlanResolver.Plan plan;
    AuthIdentity identity;
    WechatSubscriptionReservationMapper.Reserved stored;
    VerifiedWechatSubscriptionGrantResolver resolver;

    @BeforeEach void setup() {
        MetaContext.setContext(42L, 12L, "actor", "actor");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        policy.setEnabled(true); policy.setSourceTypes(Set.of("quote"));
        policy.setFieldSources(Map.of("thing1", WechatSubscriptionReservationProperties.Value.TITLE));
        mini.setAppId("wx-test"); delivery.setTemplateId("explicit-template"); delivery.setFieldMaxLengths(Map.of("thing1", 20));
        plan = new NotificationDeliveryPlanResolver.Plan(42, "source-ref", "recipient", "v1", "quote", "source", NOW, "报价提醒", "内容");
        when(plans.resolve(42, "source-ref")).thenAnswer(call -> Optional.of(plan));
        when(recipients.findActiveRecipient(42, "recipient")).thenReturn(13L);
        identity = new AuthIdentity(); identity.setPid("identity"); identity.setUserId(13L);
        identity.setProvider("wechat_mini"); identity.setAppId("wx-test"); identity.setOpenid("provider-principal");
        when(identities.selectList(any())).thenReturn(List.of(identity));
        when(reservations.available(eq("wx-test"), eq("provider-principal"), eq("explicit-template"), any()))
                .thenReturn(new WechatSubscriptionReservationMapper.Available("a".repeat(64), NOW.plusSeconds(600)));
        when(reservations.find(42, "source-ref")).thenAnswer(call -> stored);
        when(reservations.append(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> { stored = new WechatSubscriptionReservationMapper.Reserved(call.getArgument(4), call.getArgument(5)); return 1; });
        when(reservations.eligible(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(1);
        resolver = create(List.of(plans));
    }
    VerifiedWechatSubscriptionGrantResolver create(List<NotificationDeliveryPlanResolver> candidates) {
        return new VerifiedWechatSubscriptionGrantResolver(policy, delivery, mini, candidates, recipients, identities,
                consent, reservations, new LocalTransactions(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }
    static class LocalTransactions extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object value, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    }

    void configureDistinctRoutes() {
        policy.setSourceTypes(Set.of("quote","order"));policy.setFieldSources(Map.of());policy.setRoutes(Map.of());
        delivery.setTemplates(Map.of());
        delivery.setTemplateId(null);delivery.setFieldMaxLengths(Map.of());
        var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                "aura.notification.wechat-subscription.templates.explicit-template.field-max-lengths.thing1", "20",
                "aura.notification.wechat-subscription.templates.order-template.field-max-lengths.thing2", "40",
                "aura.notification.wechat-subscription.reservations.routes.quote.template-id", "explicit-template",
                "aura.notification.wechat-subscription.reservations.routes.quote.field-sources.thing1", "TITLE",
                "aura.notification.wechat-subscription.reservations.routes.order.template-id", "order-template",
                "aura.notification.wechat-subscription.reservations.routes.order.field-sources.thing2", "CONTENT"));
        var binder=new org.springframework.boot.context.properties.bind.Binder(source);
        binder.bind("aura.notification.wechat-subscription",org.springframework.boot.context.properties.bind.Bindable.ofInstance(delivery));
        binder.bind("aura.notification.wechat-subscription.reservations",org.springframework.boot.context.properties.bind.Bindable.ofInstance(policy));
    }
    @Test void distinctSourcesReserveTheirOwnTemplateAndFieldMapping() {
        configureDistinctRoutes();resolver=create(List.of(plans));
        var quote=resolver.claim(42,"source-ref").orElseThrow();
        assertThat(quote.templateId()).isEqualTo("explicit-template");
        assertThat(quote.values()).containsExactlyEntriesOf(Map.of("thing1","报价提醒"));
        stored=null;
        plan=new NotificationDeliveryPlanResolver.Plan(42,"order-ref","recipient","v1","order","order-source",NOW,"发货标题","订单发货内容");
        when(plans.resolve(42,"order-ref")).thenReturn(Optional.of(plan));
        when(reservations.find(42,"order-ref")).thenAnswer(call->stored);
        when(reservations.available(eq("wx-test"),eq("provider-principal"),eq("order-template"),any()))
                .thenReturn(new WechatSubscriptionReservationMapper.Available("b".repeat(64),NOW.plusSeconds(600)));
        var order=resolver.claim(42,"order-ref").orElseThrow();
        assertThat(order.templateId()).isEqualTo("order-template");
        assertThat(order.values()).containsExactlyEntriesOf(Map.of("thing2","订单发货内容"));
        assertThat(order.grantReference()).isNotEqualTo(quote.grantReference());
        verify(reservations).available(eq("wx-test"),eq("provider-principal"),eq("explicit-template"),any());
        verify(reservations).available(eq("wx-test"),eq("provider-principal"),eq("order-template"),any());
    }
    private static WechatSubscriptionReservationProperties.Route route(String template,
            Map<String,WechatSubscriptionReservationProperties.Value> fields) {
        var route=new WechatSubscriptionReservationProperties.Route();route.setTemplateId(template);route.setFieldSources(fields);return route;
    }
    @Test void routeChangeCannotReassignOrReplaceAnExistingReservation() {
        configureDistinctRoutes();resolver=create(List.of(plans));var grant=resolver.claim(42,"source-ref").orElseThrow();
        policy.setRoutes(Map.of("quote",route("order-template",Map.of("thing2",WechatSubscriptionReservationProperties.Value.TITLE)),
                "order",route("explicit-template",Map.of("thing1",WechatSubscriptionReservationProperties.Value.CONTENT))));
        resolver=create(List.of(plans));clearInvocations(reservations);
        assertThat(resolver.verifyReserved(42,"source-ref",grant.reservationReference())).isEmpty();
        assertThat(resolver.claim(42,"source-ref")).isEmpty();
        verify(reservations,never()).available(anyString(),anyString(),anyString(),any());
        verify(reservations,never()).append(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void changedSourceTypeCannotReuseAnotherPurposesGrant() {
        configureDistinctRoutes();resolver=create(List.of(plans));var grant=resolver.claim(42,"source-ref").orElseThrow();
        plan=new NotificationDeliveryPlanResolver.Plan(42,"source-ref","recipient","v1","order","source",NOW,"报价提醒","内容");
        clearInvocations(reservations);assertThat(resolver.claim(42,"source-ref")).isEmpty();
        assertThat(resolver.verifyReserved(42,"source-ref",grant.reservationReference())).isEmpty();
        verify(reservations,never()).available(anyString(),anyString(),anyString(),any());
    }
    @Test void missingDuplicateMixedAndMismappedRoutesFailClosedOnInitialization() {
        configureDistinctRoutes();policy.setRoutes(Map.of("quote",policy.getRoutes().get("quote")));
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        configureDistinctRoutes();policy.setRoutes(Map.of("quote",route("explicit-template",Map.of("thing1",WechatSubscriptionReservationProperties.Value.TITLE)),
                "order",route("explicit-template",Map.of("thing1",WechatSubscriptionReservationProperties.Value.CONTENT))));
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        configureDistinctRoutes();policy.getRoutes().get("order").setFieldSources(Map.of("wrong",WechatSubscriptionReservationProperties.Value.TITLE));
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        configureDistinctRoutes();policy.setFieldSources(Map.of("thing1",WechatSubscriptionReservationProperties.Value.TITLE));
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        configureDistinctRoutes();delivery.setTemplateId("legacy-template");
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        configureDistinctRoutes();policy.getRoutes().get("order").setTemplateId("unconfigured");
        assertThatThrownBy(()->create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
    }
    @Test void verifiedGrantIsReservedOnceAndReplayIsImmutable() {
        var first = resolver.claim(42, "source-ref").orElseThrow();
        assertThat(first.userPid()).isEqualTo("recipient"); assertThat(first.values()).containsExactlyEntriesOf(Map.of("thing1", "报价提醒"));
        assertThat(first.reservedAt()).isEqualTo(NOW);
        assertThat(resolver.claim(42, "source-ref")).contains(first);
        assertThat(resolver.verifyReserved(42, "source-ref", first.reservationReference())).contains(first);
        verify(reservations, times(1)).available(anyString(), anyString(), anyString(), any());
        verify(reservations, times(1)).append(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
    @Test void missingProviderGrantCannotBeReplacedByClientConsent() {
        when(reservations.available(anyString(), anyString(), anyString(), any())).thenReturn(null);
        assertThat(resolver.claim(42, "source-ref")).isEmpty();
        verify(reservations, never()).append(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
    @Test void revokedReservationDoesNotAcquireAReplacementGrant() {
        resolver.claim(42, "source-ref"); clearInvocations(reservations);
        when(reservations.eligible(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(0);
        assertThat(resolver.claim(42, "source-ref")).isEmpty();
        verify(reservations, never()).available(anyString(), anyString(), anyString(), any());
    }
    @Test void changedSourceConfigurationAndRecipientBlockOldReservation() {
        var grant = resolver.claim(42, "source-ref").orElseThrow();
        plan = new NotificationDeliveryPlanResolver.Plan(42, "source-ref", "recipient", "v2", "quote", "source", NOW, "报价提醒", "内容");
        assertThat(resolver.verifyReserved(42, "source-ref", grant.reservationReference())).isEmpty();
        plan = new NotificationDeliveryPlanResolver.Plan(42, "source-ref", "other", "v1", "quote", "source", NOW, "报价提醒", "内容");
        assertThat(resolver.verifyReserved(42, "source-ref", grant.reservationReference())).isEmpty();
    }
    @Test void revokedOrAmbiguousIdentityCannotConsumeProviderQuota() {
        when(identities.selectList(any())).thenReturn(List.of(identity, identity));
        assertThat(resolver.claim(42, "source-ref")).isEmpty();
        when(identities.selectList(any())).thenReturn(List.of());
        assertThat(resolver.claim(42, "source-ref")).isEmpty(); verifyNoInteractions(consent);
    }
    @Test void identityRebindingRejectsExistingReservation() {
        var grant = resolver.claim(42, "source-ref").orElseThrow(); identity.setOpenid("new-openid");
        assertThat(resolver.verifyReserved(42, "source-ref", grant.reservationReference())).isEmpty();
    }
    @Test void wrongTenantReferenceAndReservationNeverReadAnotherOwner() {
        assertThat(resolver.claim(99, "source-ref")).isEmpty();
        assertThat(resolver.claim(42, "unknown")).isEmpty();
        var grant = resolver.claim(42, "source-ref").orElseThrow();
        assertThat(resolver.verifyReserved(42, "source-ref", "wrong")).isEmpty();
        verify(reservations, never()).find(99, "source-ref");
        assertThat(grant.reservationReference()).hasSize(64);
    }
    @Test void missingSerializableTransactionCannotReserve() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> resolver.claim(42, "source-ref")).hasMessage("WECHAT_RESERVATION_SERIALIZABLE_TRANSACTION_REQUIRED");
        verifyNoInteractions(reservations);
    }
    @Test void dispatchVerificationAllowsMetadataShareLocksInItsShortSerializableTransaction() {
        var grant = resolver.claim(42, "source-ref").orElseThrow(); TransactionSynchronizationManager.clear();
        when(plans.resolve(42, "source-ref")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return Optional.of(plan);
        });
        assertThat(resolver.verifyReserved(42, "source-ref", grant.reservationReference())).contains(grant);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test void fieldsAreNotTruncatedAndUnsupportedSourceDoesNotConsumeConsent() {
        plan = new NotificationDeliveryPlanResolver.Plan(42, "source-ref", "recipient", "v1", "quote", "source", NOW, "x".repeat(21), "内容");
        assertThat(resolver.claim(42, "source-ref")).isEmpty();
        plan = new NotificationDeliveryPlanResolver.Plan(42, "source-ref", "recipient", "v1", "order", "source", NOW, "提醒", "内容");
        assertThat(resolver.claim(42, "source-ref")).isEmpty(); verifyNoInteractions(consent);
    }
    @Test void ambiguousAuthorizedResolversDoNotChooseARecipient() {
        resolver = create(List.of(plans, plans)); assertThat(resolver.claim(42, "source-ref")).isEmpty(); verifyNoInteractions(consent);
    }
    @Test void disabledOrIncompleteMappingsFailClosedOnInitialization() {
        policy.setEnabled(false); assertThatThrownBy(() -> create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
        policy.setEnabled(true); policy.setFieldSources(Map.of());
        assertThatThrownBy(() -> create(List.of(plans))).hasMessage("WECHAT_RESERVATION_CONFIGURATION_INVALID");
    }
}
