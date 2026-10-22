package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;

/**
 * WeChat mini-program client: exchanges a wx.login code for the caller's
 * openid/unionid via jscode2session. The session_key never leaves this class —
 * callers receive only identity fields (and it is not persisted anywhere).
 */
@Slf4j
@Component
public class WechatMiniClient {

    public record WxSession(String openid, String unionid) {}

    private static final RestClient PHONE_CLIENT = phoneClient();

    private final WechatMiniProperties properties;
    private final WechatMiniStableTokenClient stableTokens;

    public WechatMiniClient(WechatMiniProperties properties) {
        this(properties, new WechatMiniStableTokenClient(properties));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public WechatMiniClient(WechatMiniProperties properties, WechatMiniStableTokenClient stableTokens) {
        this.properties = properties; this.stableTokens = stableTokens;
    }

    public WxSession code2Session(String jsCode) {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "WeChat mini-program login is not configured (aura.wechat.mini.*)");
        }
        // Full-URI build honors the apiBase scheme (http for local stubs/tests).
        String base = validatedApiBase();
        String uri = base + "/sns/jscode2session"
                + "?appid=" + urlEncode(properties.getAppId())
                + "&secret=" + urlEncode(properties.getAppSecret())
                + "&js_code=" + urlEncode(jsCode)
                + "&grant_type=authorization_code";
        // WeChat answers with Content-Type: text/plain even for JSON bodies — the
        // Spring converters can't bind that to a POJO, so fetch the raw string and
        // parse explicitly (same pattern as WechatPcClient).
        SessionResponse body;
        try {
            String raw = PHONE_CLIENT.get().uri(URI.create(uri)).retrieve().body(String.class);
            if (isBlank(raw)) {
                throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login exchange returned an empty response");
            }
            body = json().readValue(raw, SessionResponse.class);
        } catch (RestClientException | JsonProcessingException e) {
            // The transport URI contains the app secret and login code.
            log.warn("WeChat jscode2session call failed: {}", e.getClass().getSimpleName());
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login exchange failed");
        }
        if (body == null || isBlank(body.getOpenid())) {
            int code = body == null ? 0 : body.getErrcode() == null ? -1 : body.getErrcode();
            log.warn("WeChat jscode2session rejected: errcode={}", code);
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login code rejected (" + code + ")");
        }
        return new WxSession(body.getOpenid(), body.getUnionid());
    }

    /** Server-verified grant. This value does not grant membership or roles. */
    public record VerifiedPhone(String appId, String openid, String countryCode,
                                String nationalNumber, long grantedAtEpochSeconds) {
        @Override
        public String toString() {
            return "VerifiedPhone[redacted]";
        }
    }

    /**
     * Exchanges the one-use phone code, bound by WeChat to an already-resolved
     * mini-program openid. Callers must obtain that openid from host identity,
     * never from an unauthenticated request body.
     */
    public VerifiedPhone verifyPhoneGrant(String phoneCode, String authenticatedOpenid) {
        if (isBlank(phoneCode) || isBlank(authenticatedOpenid)) {
            throw phoneFailure("WeChat phone grant requires a code and authenticated identity");
        }
        String appId = properties.getAppId();
        String secret = properties.getAppSecret();
        if (isBlank(appId) || isBlank(secret)) {
            throw phoneFailure("WeChat mini-program phone verification is not configured");
        }
        String base = validatedApiBase();
        String accessToken;
        try { accessToken = stableTokens.acquire().accessToken(); }
        catch (WechatMiniStableTokenClient.TokenFailure failure) {
            throw phoneFailure("WeChat token exchange failed");
        }
        // No retry: a phone grant is one-use. A failed exchange requires a new grant.
        JsonNode response = phonePost(base + "/wxa/business/getuserphonenumber?access_token="
                + urlEncode(accessToken), Map.of("code", phoneCode, "openid", authenticatedOpenid));
        if (!response.path("errcode").isIntegralNumber() || response.path("errcode").asLong() != 0) {
            throw phoneFailure("WeChat phone code rejected");
        }
        JsonNode phone = response.path("phone_info");
        JsonNode watermark = phone.path("watermark");
        if (!appId.equals(requiredText(watermark, "appid"))
                || !watermark.path("timestamp").isIntegralNumber()
                || watermark.path("timestamp").asLong() <= 0) {
            throw phoneFailure("WeChat phone watermark is invalid");
        }
        String country = requiredText(phone, "countryCode");
        String national = requiredText(phone, "purePhoneNumber");
        if (!country.matches("[1-9][0-9]{0,3}") || !national.matches("[0-9]{4,14}")
                || country.length() + national.length() > 15) {
            throw phoneFailure("WeChat phone number is invalid");
        }
        return new VerifiedPhone(appId, authenticatedOpenid, country, national,
                watermark.path("timestamp").asLong());
    }

    private String validatedApiBase() {
        String configured = properties.getApiBaseUrl();
        if (isBlank(configured)) {
            throw phoneFailure("WeChat phone API base is not configured");
        }
        URI base = URI.create(configured.replaceAll("/+$", ""));
        boolean loopback = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost())
                || "[::1]".equals(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || (base.getPath() != null && !base.getPath().isEmpty())
                || !("https".equals(base.getScheme()) || (loopback && "http".equals(base.getScheme())))) {
            throw phoneFailure("WeChat phone API base must use HTTPS or local test HTTP");
        }
        return base.toString();
    }

    private static RestClient phoneClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    private JsonNode phonePost(String uri, Map<String, Object> payload) {
        try {
            // Preserve a fixed byte length: WeChat rejects chunked JSON requests.
            byte[] encoded = json().writeValueAsBytes(payload);
            String raw = PHONE_CLIENT.post()
                    .uri(URI.create(uri)).contentType(MediaType.APPLICATION_JSON)
                    .contentLength(encoded.length).body(encoded).retrieve().body(String.class);
            if (isBlank(raw)) {
                throw phoneFailure("WeChat phone verification returned an empty response");
            }
            JsonNode body = json().readTree(raw);
            if (body == null || !body.isObject()) {
                throw phoneFailure("WeChat phone verification response is invalid");
            }
            if (body.has("errcode") && (!body.path("errcode").isIntegralNumber()
                    || body.path("errcode").asLong() != 0)) {
                throw phoneFailure("WeChat phone verification rejected");
            }
            return body;
        } catch (RestClientException | JsonProcessingException failure) {
            // Provider exceptions may contain the token URI or private response body.
            // Do not retain them as causes accessible to HTTP error logging/renderers.
            log.warn("WeChat phone verification failed: {}", failure.getClass().getSimpleName());
            throw phoneFailure("WeChat phone verification exchange failed");
        }
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw phoneFailure("WeChat phone verification response is incomplete");
        }
        return value.textValue();
    }

    private static RootUnCheckedException phoneFailure(String message) {
        return new RootUnCheckedException(ResponseCode.BadParam, message);
    }

    private static String urlEncode(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** apiBase is "https://api.weixin.qq.com" or an https://host[:port] override. */
    private static String hostOf(String apiBase) {
        String s = apiBase.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static com.fasterxml.jackson.databind.ObjectMapper json() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Data
    static class SessionResponse {
        private String openid;
        private String unionid;
        @com.fasterxml.jackson.annotation.JsonProperty("session_key")
        private String sessionKey;
        private Integer errcode;
        private String errmsg;
    }
}
