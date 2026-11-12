package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.notification.mapper.WechatSubscriptionConsentMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic service tests. Database transactions and callback HTTP security are qualified separately. */
class WechatSubscriptionConsentReceiverTest {
    static final String APP = "wxba5fad812f8e6fb9", ACCOUNT = "gh_97417a04a28d", TOKEN = "TEST123";
    static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");
    private final WechatSubscriptionConsentMapper mapper = mock(WechatSubscriptionConsentMapper.class);
    private final WechatSubscriptionCallbackProperties properties = properties();
    private final WechatMiniProperties mini = mini();
    private WechatSubscriptionConsentReceiver receiver;

    @BeforeEach void fixture() {
        when(mapper.insertEvent(anyString(), anyString(), anyString(), any(), anyString(), anyString())).thenReturn(1);
        when(mapper.insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(1);
        receiver = new WechatSubscriptionConsentReceiver(mapper, properties, mini, Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void restore() { TransactionSynchronizationManager.clear(); }

    @Test void confirmedOnceTemplateCreatesOneImmutableGlobalFact() throws Exception {
        var result = receive(event("subscribe_msg_popup_event", NOW, accept("quote")));
        assertThat(result.status()).isEqualTo(WechatSubscriptionConsentReceiver.Status.RECORDED);
        assertThat(result.grantsCreated()).isEqualTo(1);
        verify(mapper).insertGrant(matches("[a-f0-9]{64}"), matches("[a-f0-9]{64}"), eq(APP), eq("synthetic-openid"),
                eq("quote"), eq(NOW), eq(NOW.plusSeconds(3600)));
        var order = inOrder(mapper);
        order.verify(mapper).lockPrincipal(matches("[a-f0-9]{64}"));
        order.verify(mapper).insertEvent(anyString(), eq(APP), eq("synthetic-openid"), eq(NOW), eq("POPUP"), anyString());
    }

    @Test void replayDoesNotCreateGrantAgain() throws Exception {
        when(mapper.insertEvent(anyString(), anyString(), anyString(), any(), anyString(), anyString())).thenReturn(0);
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("quote"))).status())
                .isEqualTo(WechatSubscriptionConsentReceiver.Status.REPLAYED);
        verify(mapper, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(mapper, never()).revokeThrough(anyString(), anyString(), anyString(), any());
    }

    @Test void normalizationKeepsSameEventKeyWhenProviderListOrderChanges() throws Exception {
        String one = accept("quote"), two = accept("order");
        receive(event("subscribe_msg_popup_event", NOW, "[" + one + "," + two + "]"));
        receive(event("subscribe_msg_popup_event", NOW, "[" + two + "," + one + "]"));
        var keys = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).insertEvent(keys.capture(), anyString(), anyString(), any(), anyString(), anyString());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test void rejectionUpdatesGlobalRevocationWithoutGranting() throws Exception {
        var result = receive(event("subscribe_msg_change_event", NOW, "{\"TemplateId\":\"quote\",\"SubscribeStatusString\":\"reject\"}"));
        assertThat(result.grantsCreated()).isZero();
        verify(mapper).revokeThrough(APP, "synthetic-openid", "quote", NOW);
        verify(mapper, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test void sameSecondOrLaterRevocationBlocksDelayedAcceptance() throws Exception {
        when(mapper.revokedThrough(APP, "synthetic-openid", "quote")).thenReturn(NOW, NOW.plusSeconds(1));
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("quote"))).grantsCreated()).isZero();
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("quote"))).grantsCreated()).isZero();
        verify(mapper, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test void acceptanceAfterRevocationMayCreateANewGrant() throws Exception {
        when(mapper.revokedThrough(APP, "synthetic-openid", "quote")).thenReturn(NOW.minusSeconds(1));
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("quote"))).grantsCreated()).isEqualTo(1);
    }

    @Test void unknownTemplateExpiredAcceptanceAndSendReceiptDoNotCreateQuota() throws Exception {
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("unknown"))).grantsCreated()).isZero();
        assertThat(receive(event("subscribe_msg_popup_event", NOW.minusSeconds(3600), accept("quote"))).grantsCreated()).isZero();
        assertThat(receive(event("subscribe_msg_sent_event", NOW,
                "{\"TemplateId\":\"quote\",\"MsgID\":\"1864323726461255680\",\"ErrorCode\":0,\"ErrorStatus\":\"success\"}")).grantsCreated()).isZero();
        verify(mapper, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(mapper, never()).revokeThrough(anyString(), anyString(), anyString(), any());
    }

    @Test void invalidSignatureFutureEventAndMissingTransactionNeverWrite() throws Exception {
        String body = event("subscribe_msg_popup_event", NOW, accept("quote"));
        byte[] envelope = encrypted(body);
        assertThatThrownBy(() -> receiver.receive("aes", "0".repeat(40), epoch(), "nonce", envelope))
                .hasMessage("CALLBACK_SIGNATURE_INVALID");
        assertThatThrownBy(() -> receive(event("subscribe_msg_popup_event", NOW.plusSeconds(301), accept("quote"))))
                .hasMessage("CALLBACK_EVENT_TIME_INVALID");
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> receive(body)).hasMessage("WECHAT_CONSENT_TRANSACTION_REQUIRED");
        verifyNoInteractions(mapper);
    }

    @Test void databaseFailureCannotReturnAcknowledgedReceipt() {
        when(mapper.insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("synthetic database failure"));
        assertThatThrownBy(() -> receive(event("subscribe_msg_popup_event", NOW, accept("quote"))))
                .hasMessage("synthetic database failure");
    }

    @Test void officialDiagnosticIsVerifiedButDoesNotWriteConsent() throws Exception {
        assertThat(receive(event("debug_demo", NOW, "[]")).status())
                .isEqualTo(WechatSubscriptionConsentReceiver.Status.VERIFIED_DIAGNOSTIC);
        verifyNoInteractions(mapper);
    }

    @Test void configurationIsSnapshottedAndContainsNoDefaultTemplateOrValidity() throws Exception {
        var defaults = new WechatSubscriptionCallbackProperties();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getConsentValidity()).isNull();
        assertThat(defaults.getOnceTemplateIds()).isEmpty();
        properties.setOnceTemplateIds(Set.of("different"));
        properties.setConsentValidity(Duration.ofSeconds(1));
        assertThat(receive(event("subscribe_msg_popup_event", NOW, accept("quote"))).grantsCreated()).isEqualTo(1);
        verify(mapper).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(), eq(NOW), eq(NOW.plusSeconds(3600)));
        assertThat(properties.toString()).doesNotContain(TOKEN, "gh_97417a04a28d");
        defaults.setEnabled(true);
        assertThatThrownBy(() -> new WechatSubscriptionConsentReceiver(mapper, defaults, mini))
                .hasMessage("CALLBACK_CONFIGURATION_INVALID");
    }

    private WechatSubscriptionConsentReceiver.Receipt receive(String plaintext) throws Exception {
        byte[] bytes = encrypted(plaintext);
        String encrypted = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes).get("Encrypt").asText();
        return receiver.receive("aes", signature(TOKEN, epoch(), "nonce", encrypted), epoch(), "nonce", bytes);
    }
    static WechatSubscriptionCallbackProperties properties() {
        var p = new WechatSubscriptionCallbackProperties();
        p.setEnabled(true); p.setFormat(WechatSubscriptionCallbackVerifier.Format.JSON);
        p.setToken(TOKEN); p.setEncodingAesKey("A".repeat(43)); p.setOriginalAccountId(ACCOUNT);
        p.setConsentValidity(Duration.ofHours(1)); p.setOnceTemplateIds(Set.of("quote", "order"));
        return p;
    }
    static WechatMiniProperties mini() { var p = new WechatMiniProperties(); p.setAppId(APP); return p; }
    static String epoch() { return Long.toString(NOW.getEpochSecond()); }
    static String accept(String template) { return "{\"TemplateId\":\"" + template + "\",\"SubscribeStatusString\":\"accept\",\"PopupScene\":0}"; }
    static String event(String kind, Instant created, String items) {
        return "{\"ToUserName\":\"" + ACCOUNT + "\",\"FromUserName\":\"synthetic-openid\",\"CreateTime\":" + created.getEpochSecond()
                + ",\"MsgType\":\"event\",\"Event\":\"" + kind + "\",\"List\":" + items + "}";
    }
    static byte[] encrypted(String message) throws Exception {
        byte[] text = message.getBytes(StandardCharsets.UTF_8), app = APP.getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(20 + text.length + app.length).put(new byte[16]).putInt(text.length).put(text).put(app).array();
        int padding = 32 - plain.length % 32;
        byte[] padded = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(padded, plain.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new IvParameterSpec(new byte[16]));
        String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        return ("{\"ToUserName\":\"" + ACCOUNT + "\",\"Encrypt\":\"" + encrypted + "\"}").getBytes(StandardCharsets.UTF_8);
    }
    static String signature(String... values) throws Exception {
        Arrays.sort(values);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
    }
}
