package com.auraboot.framework.auth.wechat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WechatOAuthClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final WechatOAuthClient client = new WechatOAuthClient(http);
    private void response(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void exchangeBindsConfiguredAppAndScopeWithoutExposingIdentityOrTokens() throws Exception {
        response(200, "{\"openid\":\"verified-openid\",\"unionid\":\"verified-unionid\",\"access_token\":\"private-token\",\"scope\":\"snsapi_base\"}");
        var grant = client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base");
        assertThat(grant.appId()).isEqualTo("wx_app_original"); assertThat(grant.openId()).isEqualTo("verified-openid");
        assertThat(grant.scopes()).containsExactly("snsapi_base");
        assertThat(grant.toString()).doesNotContain("verified", "private");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(grant)).doesNotContain("verified-openid", "verified-unionid", "private-token");
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class); verify(http).send(request.capture(), any());
        assertThat(request.getValue().uri().getHost()).isEqualTo("api.weixin.qq.com");
        assertThat(request.getValue().uri().getPath()).isEqualTo("/sns/oauth2/access_token");
        assertThat(request.getValue().uri().getQuery()).contains("appid=wx_app_original", "code=code_original", "grant_type=authorization_code");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(10));
    }
    @Test void pcScopeCannotStandInForOfficialAccountPaymentIdentity() throws Exception {
        response(200, "{\"openid\":\"verified-openid\",\"access_token\":\"private-token\",\"scope\":\"snsapi_login\"}");
        assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base"))
                .isInstanceOf(com.auraboot.framework.exception.RootUnCheckedException.class);
        assertThat(client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_login").openId()).isEqualTo("verified-openid");
        response(200, "{\"openid\":\"verified-openid\",\"access_token\":\"private-token\",\"scope\":\"snsapi_userinfo\"}");
        assertThat(client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base").openId()).isEqualTo("verified-openid");
    }
    @Test void invalidChannelFactsDuplicatesAndErrorsNeverProduceAnIdentity() throws Exception {
        for (String body : List.of("", "null", "{}", "{\"errcode\":40029,\"errmsg\":\"private-secret\"}",
                "{\"openid\":\"foreign\",\"openid\":\"verified\",\"access_token\":\"secret\",\"scope\":\"snsapi_base\"}",
                "{\"openid\":\"verified\",\"access_token\":\"secret\"}",
                "{\"openid\":\"verified\",\"access_token\":\"secret\",\"scope\":\"snsapi_base\"}{}")) {
            response(200, body);
            assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base"))
                    .isInstanceOf(com.auraboot.framework.exception.RootUnCheckedException.class).hasMessageNotContaining("private-secret").hasCause(null);
        }
        response(500, "private-secret");
        assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base")).hasCause(null);
    }
    @Test void untrustedApiDestinationsAndInvalidInputFailBeforeTransport() {
        for (String base : List.of("https://evil.example", "http://api.weixin.qq.com", "https://api.weixin.qq.com.evil.example",
                "https://user@api.weixin.qq.com", "https://api.weixin.qq.com?x=1", "https://api.weixin.qq.com/path", "https://api.weixin.qq.com:444")) {
            assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base", base)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(http);
    }
    @Test void transportFailureCannotExposeTheCredentialBearingUrl() throws Exception {
        when(http.send(any(), any())).thenThrow(new java.io.IOException("https://api.weixin.qq.com?secret=private-secret"));
        assertThatThrownBy(() -> client.exchange("wx_app_original", "private-secret", "code_original", "snsapi_base"))
                .hasMessageNotContaining("private-secret").hasCause(null);
    }
}
