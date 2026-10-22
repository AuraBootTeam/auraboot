package com.auraboot.framework.auth.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Host-only stable app token exchange; credentials and tokens never become SDK payloads. */
@Component
public class WechatMiniStableTokenClient {
    public enum Reason { CONFIGURATION, TRANSPORT, RESPONSE }
    public static final class TokenFailure extends RuntimeException {
        private final Reason reason;
        TokenFailure(Reason reason) { super("WECHAT_STABLE_TOKEN_" + reason); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    public record Token(String accessToken, long expiresInSeconds) {
        @Override public String toString() { return "WechatStableToken[redacted]"; }
    }
    private final WechatMiniProperties properties;
    private final RestClient client;
    public WechatMiniStableTokenClient(WechatMiniProperties properties) {
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder().requestFactory(factory).build();
    }
    public Token acquire() {
        if (blank(properties.getAppId()) || blank(properties.getAppSecret())) throw new TokenFailure(Reason.CONFIGURATION);
        URI base = validatedApiBase(properties.getApiBaseUrl());
        String raw;
        try {
            // WeChat's gateway rejects chunked JSON requests with HTTP 412.
            byte[] payload = new ObjectMapper().writeValueAsBytes(Map.of(
                    "grant_type", "client_credential", "appid", properties.getAppId(),
                    "secret", properties.getAppSecret(), "force_refresh", false));
            raw = client.post().uri(base + "/cgi-bin/stable_token").contentType(MediaType.APPLICATION_JSON)
                    .contentLength(payload.length).body(payload).retrieve().body(String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new TokenFailure(Reason.CONFIGURATION);
        } catch (org.springframework.web.client.RestClientException failure) {
            // Provider exceptions can embed credentials; do not retain causes or raw diagnostics.
            throw new TokenFailure(Reason.TRANSPORT);
        }
        try {
            JsonNode body = new ObjectMapper().readTree(raw == null ? "" : raw);
            if (body == null || !body.isObject() || (body.has("errcode") &&
                    (!body.path("errcode").isIntegralNumber() || !body.path("errcode").canConvertToLong() || body.path("errcode").asLong() != 0))
                    || !body.path("access_token").isTextual() || blank(body.path("access_token").textValue())
                    || !body.path("expires_in").isIntegralNumber() || !body.path("expires_in").canConvertToLong()
                    || body.path("expires_in").longValue() <= 0) throw new TokenFailure(Reason.RESPONSE);
            return new Token(body.path("access_token").textValue(),body.path("expires_in").longValue());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new TokenFailure(Reason.RESPONSE); }
    }
    public static URI validatedApiBase(String configured) {
        if (blank(configured)) throw new TokenFailure(Reason.CONFIGURATION);
        URI base;
        try { base = URI.create(configured.replaceAll("/+$", "")); }
        catch (IllegalArgumentException invalid) { throw new TokenFailure(Reason.CONFIGURATION); }
        boolean loopback = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || (base.getPath() != null && !base.getPath().isEmpty())
                || !("https".equals(base.getScheme()) || (loopback && "http".equals(base.getScheme()))))
            throw new TokenFailure(Reason.CONFIGURATION);
        return base;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
