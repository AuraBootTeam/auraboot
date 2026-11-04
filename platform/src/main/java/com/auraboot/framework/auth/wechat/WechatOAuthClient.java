package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Server-only code exchange. Callers own one-use state, browser binding and domain authorization. */
@Component
public class WechatOAuthClient {
    private static final String OFFICIAL_API = "https://api.weixin.qq.com";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatOAuthClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }
    public WechatOAuthClient(HttpClient http) { this.http = java.util.Objects.requireNonNull(http); }

    public record Grant(String appId, @JsonIgnore String openId, @JsonIgnore String unionId,
            @JsonIgnore String accessToken, Set<String> scopes) {
        public Grant { scopes = Set.copyOf(scopes); }
        @Override public String toString() { return "WechatOAuthGrant[redacted]"; }
    }

    public Grant exchange(String appId, String appSecret, String code, String requiredScope) {
        return exchange(appId, appSecret, code, requiredScope, OFFICIAL_API);
    }

    /** Owned loopback HTTP fixtures use HTTP/1.1; official HTTPS keeps the client default. Never a browser parameter. */
    public Grant exchange(String appId, String appSecret, String code, String requiredScope, String apiBase) {
        if (!identifier(appId) || appSecret == null || appSecret.isBlank() || appSecret.length() > 256
                || code == null || !code.matches("[A-Za-z0-9_-]{1,512}")
                || requiredScope == null || !Set.of("snsapi_base", "snsapi_userinfo", "snsapi_login").contains(requiredScope)) throw failure();
        String base = validatedApiBase(apiBase);
        URI uri = URI.create(base + "/sns/oauth2/access_token?appid=" + enc(appId) + "&secret=" + enc(appSecret)
                + "&code=" + enc(code) + "&grant_type=authorization_code");
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
            // Owned HTTP fixtures may expose HTTP/1 only and cannot negotiate h2c upgrades.
            if (!OFFICIAL_API.equals(base)) request.version(HttpClient.Version.HTTP_1_1);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.isBlank() || body.length() > 65536) throw failure();
            var result = JSON.readTree(body);
            if (result == null || !result.isObject() || (result.has("errcode") && result.path("errcode").asInt(-1) != 0)
                    || !identifier(result.path("openid").asText(null)) || result.path("access_token").asText("").isBlank()
                    || result.path("access_token").asText().length() > 4096 || !result.path("scope").isTextual()) throw failure();
            Set<String> scopes = Set.copyOf(Arrays.asList(result.path("scope").asText().split(",")));
            if (!scopes.contains(requiredScope) && !("snsapi_base".equals(requiredScope) && scopes.contains("snsapi_userinfo"))) throw failure();
            return new Grant(appId, result.path("openid").asText(), result.path("unionid").asText(null),
                    result.path("access_token").asText(), scopes);
        } catch (java.io.IOException unreadable) {
            // Transport/parser exceptions can contain the credential-bearing URL or response.
            throw failure();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw failure();
        }
    }

    private static String validatedApiBase(String value) {
        try {
            String base = value == null ? "" : value.replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getHost() == null || !uri.getPath().isEmpty()) throw failure();
            if (OFFICIAL_API.equals(base)) return base;
            String host = uri.getHost();
            if ("http".equals(uri.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host))) return base;
            throw failure();
        } catch (IllegalArgumentException invalid) { throw failure(); }
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static RootUnCheckedException failure() {
        return new RootUnCheckedException(ResponseCode.BadParam, "WeChat authorization code exchange is unavailable or rejected");
    }
    @PreDestroy public void shutdown() { http.shutdown(); }
}
