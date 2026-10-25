package com.auraboot.framework.notification.wechat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * WeChat developer-server safe-mode boundary. This class does not grant consent or expose an HTTP endpoint.
 * See the official server-ability/message-push contract. Plain and compatibility modes are deliberately unsupported.
 */
public final class WechatSubscriptionCallbackVerifier {
    static final int MAX_BODY_BYTES = 65_536;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String appId;
    private final String originalAccountId;
    private final String token;
    private final byte[] key;
    private final Clock clock;
    private final Duration requestWindow;

    public WechatSubscriptionCallbackVerifier(String appId, String originalAccountId, String token,
            String encodingAesKey, Clock clock, Duration requestWindow) {
        if (appId == null || !appId.matches("wx[0-9a-f]{16}")
                || originalAccountId == null || !originalAccountId.matches("gh_[A-Za-z0-9_]{1,64}")
                || token == null || !token.matches("[A-Za-z0-9]{3,32}")
                || encodingAesKey == null || !encodingAesKey.matches("[A-Za-z0-9+/]{43}")
                || clock == null || requestWindow == null || requestWindow.isZero()
                || requestWindow.isNegative() || requestWindow.compareTo(Duration.ofMinutes(15)) > 0) {
            throw rejected("CALLBACK_CONFIGURATION_INVALID");
        }
        this.appId = appId;
        this.originalAccountId = originalAccountId;
        this.token = token;
        this.key = Base64.getDecoder().decode(encodingAesKey + "=");
        this.clock = clock;
        this.requestWindow = requestWindow;
    }

    /** GET URL verification uses signature, not the safe-mode POST msg_signature. No business state is changed. */
    public String verifyUrl(String signature, String timestamp, String nonce, String echo) {
        verifyRequestTime(timestamp, nonce);
        if (echo == null || echo.isEmpty() || echo.length() > 256) throw rejected("CALLBACK_ECHO_INVALID");
        verifySignature(signature, token, timestamp, nonce);
        return echo;
    }

    /** Verify the ciphertext signature before decrypting, then bind both the encrypted AppID and recipient account. */
    public VerifiedPayload verify(String encryptType, String messageSignature, String timestamp,
            String nonce, byte[] body, Format format) {
        verifyRequestTime(timestamp, nonce);
        if (!"aes".equals(encryptType) || format == null) throw rejected("CALLBACK_SAFE_MODE_REQUIRED");
        Parsed outer = parse(body, format);
        String encrypted = outer.text("Encrypt");
        if (!originalAccountId.equals(outer.text("ToUserName"))) throw rejected("CALLBACK_ACCOUNT_MISMATCH");
        verifySignature(messageSignature, token, timestamp, nonce, encrypted);
        byte[] plaintext = decrypt(encrypted);
        Parsed inner = parse(plaintext, format);
        if (!originalAccountId.equals(inner.text("ToUserName"))) throw rejected("CALLBACK_ACCOUNT_MISMATCH");
        return new VerifiedPayload(appId, inner);
    }

    private void verifyRequestTime(String timestamp, String nonce) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}") || nonce == null
                || !nonce.matches("[A-Za-z0-9_-]{1,128}")) throw rejected("CALLBACK_REQUEST_INVALID");
        Instant requested = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(requested, clock.instant()).abs().compareTo(requestWindow) > 0) {
            throw rejected("CALLBACK_REQUEST_EXPIRED");
        }
    }

    private static void verifySignature(String signature, String... parts) {
        if (signature == null || !signature.matches("[0-9a-f]{40}")) throw rejected("CALLBACK_SIGNATURE_INVALID");
        Arrays.sort(parts);
        try {
            byte[] expected = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                throw rejected("CALLBACK_SIGNATURE_INVALID");
            }
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-1 unavailable", unavailable);
        }
    }

    private byte[] decrypt(String encrypted) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encrypted);
            if (ciphertext.length == 0 || ciphertext.length % 16 != 0) throw rejected("CALLBACK_CIPHERTEXT_INVALID");
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            byte[] padded = cipher.doFinal(ciphertext);
            int padding = Byte.toUnsignedInt(padded[padded.length - 1]);
            if (padding < 1 || padding > 32 || padded.length < padding + 20) throw rejected("CALLBACK_CIPHERTEXT_INVALID");
            for (int i = padded.length - padding; i < padded.length; i++) {
                if (Byte.toUnsignedInt(padded[i]) != padding) throw rejected("CALLBACK_CIPHERTEXT_INVALID");
            }
            int end = padded.length - padding;
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(padded, 16, 4).getInt());
            byte[] expectedApp = appId.getBytes(StandardCharsets.UTF_8);
            if (length == 0 || length > MAX_BODY_BYTES || 20 + length + expectedApp.length != end) {
                throw rejected("CALLBACK_CIPHERTEXT_INVALID");
            }
            int messageEnd = 20 + (int) length;
            if (!MessageDigest.isEqual(expectedApp, Arrays.copyOfRange(padded, messageEnd, end))) {
                throw rejected("CALLBACK_APP_MISMATCH");
            }
            return Arrays.copyOfRange(padded, 20, messageEnd);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            // Never expose key material, ciphertext, decrypted bodies or provider identity through an exception.
            throw rejected("CALLBACK_CIPHERTEXT_INVALID");
        }
    }

    static Parsed parse(byte[] body, Format format) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) throw rejected("CALLBACK_BODY_INVALID");
        try {
            if (format == Format.JSON) {
                JsonNode node = JSON.readTree(body);
                if (node == null || !node.isObject()) throw rejected("CALLBACK_BODY_INVALID");
                return new Parsed(node, null);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            // The JAXP default handler prints malformed request content to stderr.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Element root = builder.parse(new ByteArrayInputStream(body)).getDocumentElement();
            if (!"xml".equals(root.getTagName())) throw rejected("CALLBACK_BODY_INVALID");
            return new Parsed(null, root);
        } catch (java.io.IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException invalid) {
            throw rejected("CALLBACK_BODY_INVALID");
        }
    }

    static CallbackRejected rejected(String code) { return new CallbackRejected(code); }
    public enum Format { JSON, XML }
    public static final class CallbackRejected extends RuntimeException {
        private CallbackRejected(String code) { super(code); }
    }
    /** Opaque verified boundary; callers cannot construct one from a frontend observation. */
    public static final class VerifiedPayload {
        private final String appId;
        private final Parsed parsed;
        private VerifiedPayload(String appId, Parsed parsed) { this.appId = appId; this.parsed = parsed; }
        String appId() { return appId; }
        Parsed parsed() { return parsed; }
        @Override public String toString() { return "VerifiedWechatCallback[redacted]"; }
    }
    record Parsed(JsonNode json, Element xml) {
        String text(String name) {
            if (json != null) {
                JsonNode value = json.get(name);
                if (value == null || !(value.isTextual() || value.isIntegralNumber())) throw rejected("CALLBACK_FIELD_INVALID");
                return bounded(value.asText());
            }
            Element found = null;
            for (var node = xml.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child && name.equals(child.getTagName())) {
                    if (found != null) throw rejected("CALLBACK_FIELD_INVALID");
                    found = child;
                }
            }
            if (found == null) throw rejected("CALLBACK_FIELD_INVALID");
            for (var child = found.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element) throw rejected("CALLBACK_FIELD_INVALID");
            }
            return bounded(found.getTextContent());
        }
        private static String bounded(String value) {
            if (value.isEmpty() || value.length() > MAX_BODY_BYTES) throw rejected("CALLBACK_FIELD_INVALID");
            return value;
        }
        @Override public String toString() { return "WechatCallbackFields[redacted]"; }
    }
}
