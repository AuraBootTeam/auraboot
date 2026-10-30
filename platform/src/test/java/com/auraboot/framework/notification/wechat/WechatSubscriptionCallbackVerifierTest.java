package com.auraboot.framework.notification.wechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static com.auraboot.framework.notification.wechat.WechatSubscriptionCallbackVerifier.*;
import static org.assertj.core.api.Assertions.*;

/** Hermetic protocol/security tests: official public vector plus synthetic encrypted provider messages, no live consent. */
class WechatSubscriptionCallbackVerifierTest {
    private static final String APP = "wxba5fad812f8e6fb9";
    private static final String ACCOUNT = "gh_97417a04a28d";
    private static final String TOKEN = "AAAAA";
    private static final String AES = "A".repeat(43);
    private static final String TIME = "1714112445";
    private static final String NONCE = "415670741";
    private final WechatSubscriptionCallbackVerifier verifier = verifier(TIME);

    @Test void decryptsOfficialSafeModeVectorAndDoesNotTreatDebugEventAsConsent() {
        // Published by WeChat at framework/server-ability/message-push.html; all credentials are public test values.
        String encrypted = "+qdx1OKCy+5JPCBFWw70tm0fJGb2Jmeia4FCB7kao+/Q5c/ohsOzQHi8khUOb05JCpj0JB4RvQMkUyus8TPxLKJGQqcvZqzDpVzazhZv6JsXUnnR8XGT740XgXZUXQ7vJVnAG+tE8NUd4yFyjPy7GgiaviNrlCTj+l5kdfMuFUPpRSrfMZuMcp3Fn2Pede2IuQrKEYwKSqFIZoNqJ4M8EajAsjLY2km32IIjdf8YL/P50F7mStwntrA2cPDrM1kb6mOcfBgRtWygb3VIYnSeOBrebufAlr7F9mFUPAJGj04=";
        VerifiedPayload result = verifier.verify("aes", "046e02f8204d34f8ba5fa3b1db94908f3df2e9b3", TIME, NONCE,
                envelope(encrypted, Format.JSON), Format.JSON);
        assertThat(result.appId()).isEqualTo(APP);
        assertThat(result.parsed().text("Event")).isEqualTo("debug_demo");
        assertThatThrownBy(() -> WechatSubscriptionCallbackEvents.decode(result))
                .hasMessage("CALLBACK_EVENT_UNSUPPORTED");
        assertThat(result.toString()).isEqualTo("VerifiedWechatCallback[redacted]");
    }

    @Test void verifiesOfficialUrlSignatureAndEchoWithoutDecrypting() {
        assertThat(verifier("1714036504").verifyUrl("f464b24fc39322e44b38aa78f5edd27bd1441696",
                "1714036504", "1514711492", "4375120948345356249")).isEqualTo("4375120948345356249");
    }

    @Test void decodesJsonSingletonAndArrayAndPreservesProviderMessageId() throws Exception {
        var singleton = decode(json("subscribe_msg_popup_event", "{\"TemplateId\":\"template-one\",\"SubscribeStatusString\":\"accept\",\"PopupScene\":\"0\"}"), Format.JSON);
        assertThat(singleton.kind()).isEqualTo(WechatSubscriptionCallbackEvents.Kind.POPUP);
        assertThat(singleton.openid()).isEqualTo("synthetic-openid");
        assertThat(singleton.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("accept"); assertThat(item.popupScene()).isZero();
        });
        var array = decode(json("subscribe_msg_popup_event", "[{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"accept\",\"PopupScene\":0},{\"TemplateId\":\"two\",\"SubscribeStatusString\":\"reject\",\"PopupScene\":2}]"), Format.JSON);
        assertThat(array.items()).extracting(WechatSubscriptionCallbackEvents.Item::status).containsExactly("accept", "reject");
        var sent = decode(json("subscribe_msg_sent_event", "{\"TemplateId\":\"one\",\"MsgID\":\"1864323726461255680\",\"ErrorCode\":0,\"ErrorStatus\":\"success\"}"), Format.JSON);
        assertThat(sent.kind()).isEqualTo(WechatSubscriptionCallbackEvents.Kind.SENT);
        assertThat(sent.items().getFirst().messageId()).isEqualTo("1864323726461255680");
        assertThat(sent.items().getFirst().errorCode()).isZero();
        assertThat(sent.toString()).doesNotContain("synthetic-openid", "1864323726461255680");
        assertThat(sent.items().getFirst().toString()).doesNotContain("1864323726461255680");
    }

    @Test void decodesXmlMultiplePopupEntriesAndRevocation() throws Exception {
        String popup = xml("subscribe_msg_popup_event", "SubscribeMsgPopupEvent", "<List><TemplateId><![CDATA[one]]></TemplateId><SubscribeStatusString>accept</SubscribeStatusString><PopupScene>0</PopupScene></List><List><TemplateId>two</TemplateId><SubscribeStatusString>reject</SubscribeStatusString><PopupScene>2</PopupScene></List>");
        assertThat(decode(popup, Format.XML).items()).extracting(WechatSubscriptionCallbackEvents.Item::templateId).containsExactly("one", "two");
        String revoke = xml("subscribe_msg_change_event", "SubscribeMsgChangeEvent", "<List><TemplateId>one</TemplateId><SubscribeStatusString>reject</SubscribeStatusString></List>");
        var event = decode(revoke, Format.XML);
        assertThat(event.kind()).isEqualTo(WechatSubscriptionCallbackEvents.Kind.CHANGE);
        assertThat(event.items().getFirst().status()).isEqualTo("reject");
        assertThat(event.items().getFirst().popupScene()).isNull();
        var sent = decode(xml("subscribe_msg_sent_event", "SubscribeMsgSentEvent",
                "<List><TemplateId>one</TemplateId><MsgID>1864323726461255680</MsgID><ErrorCode>43101</ErrorCode><ErrorStatus>reject</ErrorStatus></List>"), Format.XML);
        assertThat(sent.items().getFirst().messageId()).isEqualTo("1864323726461255680");
        assertThat(sent.items().getFirst().errorCode()).isEqualTo(43101);
    }

    @Test void numericJsonMessageIdIsNeverConvertedThroughFloatingPoint() throws Exception {
        var sent = decode(json("subscribe_msg_sent_event", "{\"TemplateId\":\"one\",\"MsgID\":1864323726461255680,\"ErrorCode\":0,\"ErrorStatus\":\"success\"}"), Format.JSON);
        assertThat(sent.items().getFirst().messageId()).isEqualTo("1864323726461255680");
    }

    @Test void rejectsWrongOuterAccountBeforeDecrypting() {
        String outer = "{\"ToUserName\":\"gh_other\",\"Encrypt\":\"not-ciphertext\"}";
        assertThatThrownBy(() -> verifier.verify("aes", "0".repeat(40), TIME, NONCE,
                outer.getBytes(StandardCharsets.UTF_8), Format.JSON)).hasMessage("CALLBACK_ACCOUNT_MISMATCH");
    }

    @ParameterizedTest @ValueSource(strings = {"", "nonce\n", "with space", "<xml>"})
    void rejectsInvalidNonce(String nonce) {
        assertThatThrownBy(() -> verifier.verifyUrl("0".repeat(40), TIME, nonce, "echo"))
                .hasMessage("CALLBACK_REQUEST_INVALID");
    }

    @Test void invalidConfigurationCannotExposeSecretsInErrors() {
        assertThatThrownBy(() -> new WechatSubscriptionCallbackVerifier(APP, ACCOUNT, "secret-not-valid!", AES,
                Clock.systemUTC(), Duration.ofMinutes(10))).hasMessage("CALLBACK_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> new WechatSubscriptionCallbackVerifier(APP, ACCOUNT, TOKEN, AES,
                Clock.systemUTC(), Duration.ofDays(1))).hasMessage("CALLBACK_CONFIGURATION_INVALID");
    }

    @Test void plaintextCannotConstructVerifiedAuthority() {
        assertThatThrownBy(() -> WechatSubscriptionCallbackEvents.decode(null)).hasMessage("CALLBACK_VERIFICATION_REQUIRED");
        assertThat(VerifiedPayload.class.getDeclaredConstructors()).allSatisfy(constructor ->
                assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThatThrownBy(() -> verifier.verify("raw", "0".repeat(40), TIME, NONCE, new byte[0], Format.JSON))
                .hasMessage("CALLBACK_SAFE_MODE_REQUIRED");
    }

    @Test void rejectsCiphertextTamperingAndUsingUrlSignatureForPost() throws Exception {
        String ciphertext = encrypt(json("subscribe_msg_change_event", "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"}"), APP, 0);
        String signature = sign(TOKEN, TIME, NONCE, ciphertext);
        String changed = (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1);
        assertThatThrownBy(() -> verifier.verify("aes", signature, TIME, NONCE, envelope(changed, Format.JSON), Format.JSON))
                .hasMessage("CALLBACK_SIGNATURE_INVALID");
        assertThatThrownBy(() -> verifier.verify("aes", sign(TOKEN, TIME, NONCE), TIME, NONCE, envelope(ciphertext, Format.JSON), Format.JSON))
                .hasMessage("CALLBACK_SIGNATURE_INVALID");
    }

    @Test void rejectsWrongEncryptedAppAndRecipientAccount() throws Exception {
        String body = json("subscribe_msg_change_event", "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"}");
        assertThatThrownBy(() -> verifyEncrypted(encrypt(body, "wx0000000000000000", 0), Format.JSON)).hasMessage("CALLBACK_APP_MISMATCH");
        assertThatThrownBy(() -> decode(body.replace(ACCOUNT, "gh_other"), Format.JSON)).hasMessage("CALLBACK_ACCOUNT_MISMATCH");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void rejectsSignedMalformedFrame(int corruption) throws Exception {
        String body = json("subscribe_msg_change_event", "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"}");
        assertThatThrownBy(() -> verifyEncrypted(encrypt(body, APP, corruption), Format.JSON)).hasMessage("CALLBACK_CIPHERTEXT_INVALID");
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "1714112445000", "not-a-time", "1714111245", "1714113645"})
    void rejectsMissingInvalidAndStaleRequestTime(String time) {
        assertThatThrownBy(() -> verifier.verifyUrl("0".repeat(40), time, NONCE, "echo")).isInstanceOf(CallbackRejected.class);
    }

    @Test void acceptsTimeWindowBoundaryButNotBeyond() throws Exception {
        String boundary = "1714111845";
        assertThat(verifier.verifyUrl(sign(TOKEN, boundary, NONCE), boundary, NONCE, "echo")).isEqualTo("echo");
        String expired = "1714111844";
        assertThatThrownBy(() -> verifier.verifyUrl(sign(TOKEN, expired, NONCE), expired, NONCE, "echo"))
                .hasMessage("CALLBACK_REQUEST_EXPIRED");
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"ToUserName\":\"gh_97417a04a28d\",\"Encrypt\":\"a\",\"Encrypt\":\"b\"}",
            "{\"ToUserName\":\"gh_97417a04a28d\",\"Encrypt\":\"a\"} {}",
            "[]"})
    void rejectsAmbiguousJsonBeforeDecrypting(String body) {
        assertThatThrownBy(() -> verifier.verify("aes", "0".repeat(40), TIME, NONCE, body.getBytes(StandardCharsets.UTF_8), Format.JSON))
                .hasMessage("CALLBACK_BODY_INVALID");
    }

    @Test void rejectsXxeDuplicateXmlFieldsAndOversizedBodies() {
        String xxe = "<!DOCTYPE xml [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><xml><ToUserName>&xxe;</ToUserName><Encrypt>a</Encrypt></xml>";
        assertThatThrownBy(() -> verifier.verify("aes", "0".repeat(40), TIME, NONCE, xxe.getBytes(StandardCharsets.UTF_8), Format.XML))
                .hasMessage("CALLBACK_BODY_INVALID");
        String duplicate = "<xml><ToUserName>" + ACCOUNT + "</ToUserName><ToUserName>gh_other</ToUserName><Encrypt>a</Encrypt></xml>";
        assertThatThrownBy(() -> verifier.verify("aes", "0".repeat(40), TIME, NONCE, duplicate.getBytes(StandardCharsets.UTF_8), Format.XML))
                .hasMessage("CALLBACK_FIELD_INVALID");
        assertThatThrownBy(() -> verifier.verify("aes", "0".repeat(40), TIME, NONCE, new byte[MAX_BODY_BYTES + 1], Format.JSON))
                .hasMessage("CALLBACK_BODY_INVALID");
    }

    @ParameterizedTest @ValueSource(strings = {
            "[]", "[false]", "null",
            "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"accept\"}",
            "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"ban\"}",
            "[{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"},{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"}]"})
    void rejectsMalformedOrNonRevocationChangeEvent(String list) {
        assertThatThrownBy(() -> decode(json("subscribe_msg_change_event", list), Format.JSON)).isInstanceOf(CallbackRejected.class);
    }

    @Test void xmlDuplicateWrappersAndNestedScalarAreRejected() {
        String item = "<List><TemplateId>one</TemplateId><SubscribeStatusString>reject</SubscribeStatusString></List>";
        String body = xml("subscribe_msg_change_event", "SubscribeMsgChangeEvent", item);
        assertThatThrownBy(() -> decode(body.replace("</xml>", "<SubscribeMsgChangeEvent>" + item + "</SubscribeMsgChangeEvent></xml>"), Format.XML))
                .hasMessage("CALLBACK_LIST_INVALID");
        assertThatThrownBy(() -> decode(body.replace("<TemplateId>one</TemplateId>", "<TemplateId><nested>one</nested></TemplateId>"), Format.XML))
                .hasMessage("CALLBACK_FIELD_INVALID");
    }

    @Test void emptyAndOversizedProviderListsAreRejected() {
        assertThatThrownBy(() -> decode(xml("subscribe_msg_popup_event", "SubscribeMsgPopupEvent", ""), Format.XML))
                .hasMessage("CALLBACK_LIST_INVALID");
        String item = "{\"TemplateId\":\"one\",\"SubscribeStatusString\":\"reject\"}";
        assertThatThrownBy(() -> decode(json("subscribe_msg_change_event", "[" + String.join(",", java.util.Collections.nCopies(21, item)) + "]"), Format.JSON))
                .hasMessage("CALLBACK_LIST_INVALID");
    }

    private static WechatSubscriptionCallbackVerifier verifier(String seconds) {
        return new WechatSubscriptionCallbackVerifier(APP, ACCOUNT, TOKEN, AES,
                Clock.fixed(Instant.ofEpochSecond(Long.parseLong(seconds)), ZoneOffset.UTC), Duration.ofMinutes(10));
    }
    private WechatSubscriptionCallbackEvents.Event decode(String body, Format format) throws Exception {
        return WechatSubscriptionCallbackEvents.decode(verifyEncrypted(encrypt(body, APP, 0), format));
    }
    private VerifiedPayload verifyEncrypted(String encrypted, Format format) throws Exception {
        return verifier.verify("aes", sign(TOKEN, TIME, NONCE, encrypted), TIME, NONCE, envelope(encrypted, format), format);
    }
    private static byte[] envelope(String ciphertext, Format format) {
        return (format == Format.JSON ? "{\"ToUserName\":\"" + ACCOUNT + "\",\"Encrypt\":\"" + ciphertext + "\"}"
                : "<xml><ToUserName>" + ACCOUNT + "</ToUserName><Encrypt>" + ciphertext + "</Encrypt></xml>")
                .getBytes(StandardCharsets.UTF_8);
    }
    private static String json(String event, String list) {
        return "{\"ToUserName\":\"" + ACCOUNT + "\",\"FromUserName\":\"synthetic-openid\",\"CreateTime\":" + TIME
                + ",\"MsgType\":\"event\",\"Event\":\"" + event + "\",\"List\":" + list + "}";
    }
    private static String xml(String event, String wrapper, String list) {
        return "<xml><ToUserName>" + ACCOUNT + "</ToUserName><FromUserName>synthetic-openid</FromUserName><CreateTime>"
                + TIME + "</CreateTime><MsgType>event</MsgType><Event>" + event + "</Event><" + wrapper + ">" + list + "</" + wrapper + "></xml>";
    }
    private static String encrypt(String message, String app, int corruption) throws Exception {
        byte[] text = message.getBytes(StandardCharsets.UTF_8), appBytes = app.getBytes(StandardCharsets.UTF_8);
        byte[] frame = ByteBuffer.allocate(20 + text.length + appBytes.length).put(new byte[16])
                .putInt(corruption == 3 ? Integer.MAX_VALUE : text.length).put(text).put(appBytes).array();
        int padding = 32 - frame.length % 32;
        byte[] padded = Arrays.copyOf(frame, frame.length + padding);
        Arrays.fill(padded, frame.length, padded.length, (byte) padding);
        if (corruption == 1) padded[padded.length - 1] = 0;
        if (corruption == 2) padded[padded.length - 2] = 33;
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new IvParameterSpec(new byte[16]));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }
    private static String sign(String... parts) throws Exception {
        Arrays.sort(parts);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
    }
}
