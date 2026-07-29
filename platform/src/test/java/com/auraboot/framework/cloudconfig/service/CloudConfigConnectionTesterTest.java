package com.auraboot.framework.cloudconfig.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CloudConfigConnectionTester}.
 *
 * <p>The tester delegates to a static HttpClient for live probes; this suite
 * focuses on the deterministic, non-network branches:
 * <ul>
 *   <li>"config not found" early return</li>
 *   <li>missing-required-field validation for each provider</li>
 *   <li>unknown-service-type / unknown-provider-code default branches</li>
 *   <li>JSON parse failure path</li>
 * </ul>
 * Live network probes are left to integration / contract tests.
 */
@ExtendWith(MockitoExtension.class)
class CloudConfigConnectionTesterTest {

    @Mock
    private CloudConfigService cloudConfigService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CloudConfigConnectionTester tester;

    private CloudConfig config(String serviceType, String providerCode, String json) {
        CloudConfig c = new CloudConfig();
        c.setPid("pid-1");
        c.setServiceType(serviceType);
        c.setProviderCode(providerCode);
        c.setConfig(json);
        return c;
    }

    @Test
    void testConnection_configNotFound_returnsError() {
        when(cloudConfigService.getByPidDecrypted("missing")).thenReturn(null);

        Map<String, Object> result = tester.testConnection("missing");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("message")).isEqualTo("Config not found");
    }

    @Test
    void testConnection_invalidJson_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("sms", "tencent_sms", "{not-json"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
    }

    @Test
    void testConnection_unknownServiceType_returnsOkWithMessage() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("storage", "any", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat((String) result.get("message")).contains("No test available for service type");
    }

    @Test
    void testConnection_llm_missingApiKey_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("llm", "anthropic",
                        "{\"apiFormat\":\"messages\",\"defaultModel\":\"claude-sonnet-4-6\"}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("apiKey");
    }

    @Test
    void testConnection_llm_invalidApiFormat_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("llm", "custom",
                        "{\"apiKey\":\"sk-test\",\"apiFormat\":\"legacy_completion\",\"defaultModel\":\"m\"}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("apiFormat");
    }

    @Test
    void testConnection_llm_openAiCompatibleConfig_returnsOk() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("llm", "deepseek",
                        "{\"apiKey\":\"sk-test\",\"apiFormat\":\"chat_completions\","
                                + "\"baseUrl\":\"https://api.deepseek.com\",\"defaultModel\":\"deepseek-chat\"}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat((String) result.get("message")).contains("LLM provider config validated");
    }

    @Test
    void testConnection_smsUnknownProvider_returnsOkWithMessage() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("sms", "unknown_sms", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat((String) result.get("message")).contains("No test available for SMS provider");
    }

    @Test
    void testConnection_oauthUnknownProvider_returnsOkWithMessage() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("oauth", "unknown_oauth", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat((String) result.get("message")).contains("No test available for OAuth provider");
    }

    // -----------------------------------------------------------------
    // Missing-field validation paths (no network call needed)
    // -----------------------------------------------------------------

    @Test
    void testConnection_tencentSms_missingFields_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("sms", "tencent_sms", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("secretId");
    }

    @Test
    void testConnection_aliyunSms_missingFields_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("sms", "aliyun_sms", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("accessKeyId");
    }

    @Test
    void testConnection_email_missingHost_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("email", "smtp", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("host");
    }

    @Test
    void testConnection_email_privateHost_isRejected() {
        // SEC-20260723-07: an SMTP host pointing at an internal/private address must be
        // rejected by the SSRF guard before any connection attempt (blind-SSRF / internal
        // port probe). Without the guard this would attempt a live connection and return
        // "SMTP test failed" instead.
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("email", "smtp", "{\"host\":\"10.0.0.1\",\"port\":25}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("not allowed");
    }

    @Test
    void testConnection_googleOAuth_missingClientId_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("oauth", "google", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("clientId");
    }

    @Test
    void testConnection_wechatOAuth_missingFields_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("oauth", "wechat_web", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("appId");
    }

    @Test
    void testConnection_appleOAuth_missingFields_returnsError() {
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("oauth", "apple", "{}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message")).contains("clientId");
    }

    // -----------------------------------------------------------------
    // Security: WeChat appSecret must never leak into the error message.
    // The cgi-bin/token contract puts the secret in the URL query, and a
    // URI-illegal appSecret makes URI.create throw an exception whose message
    // contains the full URL — which must NOT be echoed back to the caller.
    // -----------------------------------------------------------------

    @Test
    void testConnection_wechatOAuth_malformedUrl_doesNotLeakSecret() {
        String secret = "s3cr3t with space <leak>";
        when(cloudConfigService.getByPidDecrypted("pid-1"))
                .thenReturn(config("oauth", "wechat_web",
                        "{\"appId\":\"wx-app-id\",\"appSecret\":\"" + secret + "\"}"));

        Map<String, Object> result = tester.testConnection("pid-1");

        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("message"))
                .doesNotContain(secret)
                .doesNotContain("s3cr3t");
    }
}
