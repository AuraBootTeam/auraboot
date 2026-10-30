package com.auraboot.framework.auth.wechat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WechatPcClientTest {
    @Test void pcLoginUsesSharedCodeExchangeAndProfileNeverOverridesVerifiedIdentity() {
        var properties = new WechatPcProperties(); properties.setAppId("website_app"); properties.setAppSecret("website_secret");
        var oauth = mock(WechatOAuthClient.class); var profiles = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(oauth.exchange("website_app", "website_secret", "code_one", "snsapi_login", properties.getApiBaseUrl()))
                .thenReturn(new WechatOAuthClient.Grant("website_app", "original_openid", "original_unionid", "private_token", Set.of("snsapi_login")));
        when(profiles.get().uri(any(URI.class)).retrieve().body(String.class))
                .thenReturn("{\"openid\":\"foreign_openid\",\"nickname\":\"Profile hint\"}");
        var result = new WechatPcClient(properties, oauth, profiles).exchange("code_one");
        assertThat(result.openid()).isEqualTo("original_openid"); assertThat(result.unionid()).isEqualTo("original_unionid");
        assertThat(result.nickname()).isEqualTo("Profile hint");
    }
}
