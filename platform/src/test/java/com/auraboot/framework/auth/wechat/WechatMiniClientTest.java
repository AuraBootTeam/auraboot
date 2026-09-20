package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        WechatMiniProperties properties = new WechatMiniProperties();
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
}
