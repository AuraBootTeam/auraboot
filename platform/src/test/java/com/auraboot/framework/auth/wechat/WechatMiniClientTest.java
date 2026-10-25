package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CopyOnWriteArrayList;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the jscode2session exchange against a real HTTP stub.
 * WeChat answers with Content-Type: text/plain even for JSON bodies — both the
 * success shape and the errcode shape — so the client must fetch the raw body
 * and parse it explicitly; a converter-bound POJO read fails with
 * "no suitable HttpMessageConverter" and masks the real WeChat errcode.
 */
class WechatMiniClientTest {

    private HttpServer server;
    private WechatMiniClient client;
    private WechatMiniProperties properties;
    private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
    private static final String PHONE = """
            {"errcode":0,"phone_info":{"purePhoneNumber":"13800138000","countryCode":"86",
            "watermark":{"appid":"wx-test-appid","timestamp":1791730000}}}
            """;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        properties = new WechatMiniProperties();
        properties.setAppId("wx-test-appid");
        properties.setAppSecret("test-secret");
        properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
        client = new WechatMiniClient(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stubExchange(String contentType, String body) {
        server.createContext("/sns/jscode2session", (HttpExchange exchange) -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
    }

    @Test
    void parsesTextPlainSuccessBody() {
        stubExchange("text/plain",
                "{\"openid\":\"o-abc\",\"session_key\":\"sk\",\"unionid\":\"u-1\"}");
        WechatMiniClient.WxSession session = client.code2Session("valid-code");
        assertThat(session.openid()).isEqualTo("o-abc");
        assertThat(session.unionid()).isEqualTo("u-1");
    }

    @Test
    void surfacesWeChatErrcodeFromTextPlainErrorBody() {
        stubExchange("text/plain", "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        assertThatThrownBy(() -> client.code2Session("stale-code"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("rejected (40029)");
    }

    @Test
    void rejectsWhenCredentialsMissing() {
        WechatMiniClient unconfigured = new WechatMiniClient(new WechatMiniProperties());
        assertThatThrownBy(() -> unconfigured.code2Session("any-code"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("not configured");
    }

    private void phoneEndpoints(String tokenResponse, int status, String phoneResponse) {
        server.createContext("/cgi-bin/stable_token", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, tokenResponse);
        });
        server.createContext("/wxa/business/getuserphonenumber", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, phoneResponse);
        });
    }

    private static void respond(HttpExchange exchange, int status, String response) throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @Test
    void verifiesPhoneUsingStableTokenAndServerResolvedOpenid() throws Exception {
        phoneEndpoints("{\"access_token\":\"synthetic-token\",\"expires_in\":7200}", 200, PHONE);
        var phone = client.verifyPhoneGrant("synthetic-grant", "authenticated-openid");
        assertThat(phone.appId()).isEqualTo("wx-test-appid");
        assertThat(phone.openid()).isEqualTo("authenticated-openid");
        assertThat(phone.countryCode()).isEqualTo("86");
        assertThat(phone.nationalNumber()).isEqualTo("13800138000");
        assertThat(phone.grantedAtEpochSeconds()).isEqualTo(1791730000L);
        assertThat(phone.toString()).doesNotContain("13800138000", "authenticated-openid");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("POST /cgi-bin/stable_token ");
        var tokenPayload = new ObjectMapper().readTree(requests.get(0).substring(requests.get(0).indexOf('{')));
        assertThat(tokenPayload.path("grant_type").asText()).isEqualTo("client_credential");
        assertThat(tokenPayload.path("appid").asText()).isEqualTo("wx-test-appid");
        assertThat(tokenPayload.path("secret").asText()).isEqualTo("test-secret");
        assertThat(tokenPayload.path("force_refresh").asBoolean()).isFalse();
        assertThat(requests.get(1)).startsWith("POST /wxa/business/getuserphonenumber?access_token=synthetic-token ");
        var grant = new ObjectMapper().readTree(requests.get(1).substring(requests.get(1).indexOf('{')));
        assertThat(grant.path("code").asText()).isEqualTo("synthetic-grant");
        assertThat(grant.path("openid").asText()).isEqualTo("authenticated-openid");
    }

    @Test
    void sendsFixedLengthJsonForWechatTokenAndPhoneGateways() throws Exception {
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpHandler requireFixedLength = exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            boolean fixed = Integer.toString(body.length).equals(length)
                    && exchange.getRequestHeaders().getFirst("Transfer-Encoding") == null;
            if (!fixed) {
                respond(exchange, 412, "");
                return;
            }
            accepted.incrementAndGet();
            respond(exchange, 200, exchange.getRequestURI().getPath().endsWith("stable_token")
                    ? "{\"access_token\":\"synthetic-token\",\"expires_in\":7200}" : PHONE);
        };
        server.createContext("/cgi-bin/stable_token", requireFixedLength);
        server.createContext("/wxa/business/getuserphonenumber", requireFixedLength);
        var phone = client.verifyPhoneGrant("synthetic-grant", "authenticated-openid");
        assertThat(phone.nationalNumber()).isEqualTo("13800138000");
        assertThat(accepted.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errcode\":40029,\"errmsg\":\"private-provider-detail\"}",
            "{\"errcode\":4294967296,\"phone_info\":{}}",
            "{}", "[]", "null", "not-json",
            "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"another-app\",\"timestamp\":1791730000}}}",
            "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"invalid\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"wx-test-appid\",\"timestamp\":1791730000}}}",
            "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"wx-test-appid\",\"timestamp\":0}}}"
    })
    void rejectsInvalidGrantWithoutRetryOrProviderDetailLeak(String response) {
        phoneEndpoints("{\"access_token\":\"synthetic-token\",\"expires_in\":7200}", 200, response);
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", "authenticated-openid"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageNotContaining("private-provider-detail")
                .hasMessageNotContaining("synthetic-token")
                .hasMessageNotContaining("13800138000");
        assertThat(requests).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"errcode\":40125}",
            "{\"access_token\":\"synthetic-token\",\"expires_in\":0}"})
    void rejectedTokenNeverConsumesPhoneCode(String response) {
        phoneEndpoints(response, 200, PHONE);
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", "authenticated-openid"))
                .isInstanceOf(RootUnCheckedException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void transportErrorNeverLeaksTokenUriOrRetriesPhoneGrant() {
        phoneEndpoints("{\"access_token\":\"synthetic-token\",\"expires_in\":7200}", 503,
                "private-provider-detail");
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", "authenticated-openid"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessage("WeChat phone verification exchange failed")
                .hasNoCause();
        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectsMissingIdentityCredentialsAndInsecureRemoteTransportBeforeNetwork() {
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", " "))
                .isInstanceOf(RootUnCheckedException.class);
        properties.setAppSecret("");
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", "authenticated-openid"))
                .isInstanceOf(RootUnCheckedException.class);
        properties.setAppSecret("test-secret");
        properties.setApiBaseUrl("http://api.weixin.qq.com");
        assertThatThrownBy(() -> client.verifyPhoneGrant("synthetic-grant", "authenticated-openid"))
                .isInstanceOf(RootUnCheckedException.class);
        assertThat(requests).isEmpty();
    }
    @Test
    void loginTransportFailureDoesNotExposeSecretCodeOrProviderResponse() {
        server.createContext("/sns/jscode2session", exchange -> respond(exchange, 503, "private-login-provider-detail"));
        assertThatThrownBy(() -> client.code2Session("private-login-code"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessage("WeChat login exchange failed").hasNoCause();
    }

    @Test
    void loginMalformedBodyDoesNotExposePrivateProviderPayload() {
        stubExchange("text/plain", "private-login-provider-detail");
        assertThatThrownBy(() -> client.code2Session("private-login-code"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessage("WeChat login exchange failed").hasNoCause();
    }

}
