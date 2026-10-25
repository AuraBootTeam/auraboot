package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.notification.dto.WechatSubscriptionObservationRequest;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.mapper.WechatSubscriptionObservationMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic service contract tests; persistence and JWT are covered separately by real-stack tests. */
class WechatSubscriptionPreferenceServiceTest {
    static final String APP = "wx0000000000000063", IDENTITY = "01M5J4X57YXBZTP741AP19EYGV";
    final AuthIdentityMapper identities = mock(AuthIdentityMapper.class);
    final NotificationMapper recipients = mock(NotificationMapper.class);
    final WechatSubscriptionObservationMapper observations = mock(WechatSubscriptionObservationMapper.class);
    final WechatSubscriptionPreferenceProperties settings = new WechatSubscriptionPreferenceProperties();
    final WechatMiniProperties mini = new WechatMiniProperties();
    AuthIdentity identity;
    WechatSubscriptionPreferenceService service;

    @BeforeEach void setup() {
        MetaContext.setContext(42L, 12L, "user-pid", "user");
        mini.setAppId(APP); settings.setEnabled(true);
        settings.setTemplates(new HashMap<>(Map.of("quote", "synthetic-quote", "order", "synthetic-order")));
        identity = new AuthIdentity(); identity.setPid(IDENTITY); identity.setUserId(12L);
        identity.setProvider("wechat_mini"); identity.setAppId(APP); identity.setOpenid("synthetic-observer");
        when(recipients.findActiveRecipient(42L, "user-pid")).thenReturn(12L);
        when(identities.selectList(any())).thenReturn(List.of(identity));
        when(observations.append(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        final Map<String, String> stored = new HashMap<>();
        doAnswer(call -> { stored.putIfAbsent(call.getArgument(4), call.getArgument(5)); return 1; })
                .when(observations).append(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        when(observations.storedHash(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.get(call.<String>getArgument(4)));
        service = create();
    }
    @AfterEach void clear() { MetaContext.clear(); }
    WechatSubscriptionPreferenceService create() { return new WechatSubscriptionPreferenceService(settings, mini, identities, recipients, observations); }
    static WechatSubscriptionObservationRequest.Choice choice(String id, String status) { return new WechatSubscriptionObservationRequest.Choice(id, status); }
    static WechatSubscriptionObservationRequest request(String key, WechatSubscriptionObservationRequest.Choice... choices) {
        return new WechatSubscriptionObservationRequest(List.of(choices), key);
    }

    @Test void configuredCatalogIsSortedImmutableAndBoundToCurrentActiveIdentity() {
        settings.getTemplates().clear();
        assertThat(service.catalog().records()).containsExactly(
                new WechatSubscriptionPreferenceService.Template("order", "synthetic-order", "once"),
                new WechatSubscriptionPreferenceService.Template("quote", "synthetic-quote", "once"));
        verify(recipients).findActiveRecipient(42L, "user-pid"); verify(identities).selectList(any());
        verifyNoInteractions(observations);
    }
    @Test void disabledCatalogHasNoTemplatesOrObservationWrites() {
        settings.setEnabled(false); mini.setAppId(null); var disabled = create(); MetaContext.clear();
        assertThat(disabled.catalog().records()).isEmpty();
        assertThatThrownBy(() -> disabled.observe(request("request-1", choice("synthetic-quote", "accept")))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(identities, recipients, observations);
    }
    @Test void duplicateTemplateAndMalformedAppConfigurationFailClosed() {
        settings.setTemplates(Map.of("quote", "same", "order", "same"));
        assertThatThrownBy(this::create).hasMessage("SUBSCRIPTION_PREFERENCE_CONFIGURATION_INVALID");
        settings.setTemplates(Map.of("quote", "valid")); mini.setAppId("invalid");
        assertThatThrownBy(this::create).hasMessage("SUBSCRIPTION_PREFERENCE_CONFIGURATION_INVALID");
    }
    @Test void recordsOnlyTheCurrentOwnerAndCanonicalizesReorderedReplay() {
        var first = request("request-1", choice("synthetic-quote", "accept"), choice("synthetic-order", "reject"));
        var replay = request("request-1", choice("synthetic-order", "reject"), choice("synthetic-quote", "accept"));
        assertThat(service.observe(first)).isEqualTo(new WechatSubscriptionPreferenceService.Recorded(true, false));
        assertThat(service.observe(replay)).isEqualTo(new WechatSubscriptionPreferenceService.Recorded(true, false));
        verify(observations, times(2)).append(eq(42L), eq(12L), eq(APP), eq(IDENTITY), eq("request-1"),
                matches("[0-9a-f]{64}"), eq("[{\"templateId\":\"synthetic-order\",\"status\":\"reject\"},{\"templateId\":\"synthetic-quote\",\"status\":\"accept\"}]"));
    }
    @Test void changedIntentOrReplacedIdentityCannotReuseAnExistingObservationKey() {
        service.observe(request("request-1", choice("synthetic-quote", "accept")));
        assertThatThrownBy(() -> service.observe(request("request-1", choice("synthetic-quote", "reject")))).isInstanceOf(RuntimeException.class);
        when(observations.storedHash(42L, 12L, APP, IDENTITY, "request-1")).thenReturn(null);
        assertThatThrownBy(() -> service.observe(request("request-1", choice("synthetic-quote", "accept")))).isInstanceOf(RuntimeException.class);
    }
    @Test void missingInactiveOrMismatchedAccountCannotReadOrWritePreferences() {
        when(recipients.findActiveRecipient(42L, "user-pid")).thenReturn(99L);
        assertThatThrownBy(service::catalog).isInstanceOf(RuntimeException.class);
        MetaContext.clear();
        assertThatThrownBy(() -> service.observe(request("request-1", choice("synthetic-quote", "accept")))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(identities, observations);
    }
    @Test void missingOrAmbiguousWechatIdentityCannotWrite() {
        for (var rows : List.of(List.<AuthIdentity>of(), List.of(identity, identity))) {
            when(identities.selectList(any())).thenReturn(rows);
            assertThatThrownBy(() -> service.observe(request("request-1", choice("synthetic-quote", "accept")))).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(observations);
    }
    @Test void identityFieldsAreRevalidatedEvenIfMapperReturnsForeignRecord() {
        identity.setUserId(99L);
        assertThatThrownBy(service::catalog).isInstanceOf(RuntimeException.class);
        identity.setUserId(12L); identity.setAppId("wx0000000000000099");
        assertThatThrownBy(service::catalog).isInstanceOf(RuntimeException.class);
        identity.setAppId(APP); identity.setProvider("password");
        assertThatThrownBy(service::catalog).isInstanceOf(RuntimeException.class);
        identity.setProvider("wechat_mini"); identity.setOpenid(" ");
        assertThatThrownBy(service::catalog).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(observations);
    }
    @Test void invalidChoicesAreRejectedBeforeLookingUpOrPersistingIdentity() {
        var invalid = Arrays.asList(null, request("invalid key", choice("synthetic-quote", "accept")),
                request("key"), request("key", choice("unknown-template", "accept")),
                request("key", choice("synthetic-quote", "unknown")),
                request("key", choice("synthetic-quote", null)),
                request("key", choice("synthetic-quote", "accept"), choice("synthetic-quote", "reject")));
        for (var value : invalid) assertThatThrownBy(() -> service.observe(value)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(identities, recipients, observations);
    }
    @Test void everyWechatCallbackChoiceIsAnObservationWithoutAnyDeliveryClaim() {
        for (String status : List.of("accept", "reject", "ban", "filter"))
            assertThat(service.observe(request("key-" + status, choice("synthetic-quote", status))).delivered()).isFalse();
    }
}
