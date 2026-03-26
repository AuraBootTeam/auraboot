package com.auraboot.framework.cloudconfig.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Validates connectivity for cloud vendor configurations by performing
 * lightweight probe calls against each provider's API.
 *
 * @since 7.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudConfigConnectionTester {

    private final CloudConfigService cloudConfigService;
    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Test connectivity for the given cloud config.
     *
     * @param pid config PID
     * @return result map with "status" ("ok" or "error") and "message"
     */
    public Map<String, Object> testConnection(String pid) {
        CloudConfig config = cloudConfigService.getByPidDecrypted(pid);
        if (config == null) {
            return Map.of("status", "error", "message", "Config not found");
        }

        try {
            JsonNode cfg = objectMapper.readTree(config.getConfig());
            return switch (config.getServiceType()) {
                case "sms" -> testSms(config.getProviderCode(), cfg);
                case "email" -> testEmail(cfg);
                case "oauth" -> testOAuth(config.getProviderCode(), cfg);
                default -> Map.of("status", "ok",
                        "message", "No test available for service type: " + config.getServiceType());
            };
        } catch (Exception e) {
            log.error("Connection test failed for pid={}", pid, e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SMS Tests
    // -------------------------------------------------------------------------

    private Map<String, Object> testSms(String providerCode, JsonNode cfg) {
        return switch (providerCode) {
            case "tencent_sms" -> testTencentSms(cfg);
            case "aliyun_sms" -> testAliyunSms(cfg);
            default -> Map.of("status", "ok",
                    "message", "No test available for SMS provider: " + providerCode);
        };
    }

    private Map<String, Object> testTencentSms(JsonNode cfg) {
        String secretId = cfg.path("secretId").asText("");
        String secretKey = cfg.path("secretKey").asText("");
        String appId = cfg.path("appId").asText("");

        if (secretId.isBlank() || secretKey.isBlank() || appId.isBlank()) {
            return Map.of("status", "error", "message", "Missing required fields: secretId, secretKey, appId");
        }

        try {
            var credential = new com.tencentcloudapi.common.Credential(secretId, secretKey);
            String region = cfg.path("region").asText("ap-guangzhou");
            var client = new com.tencentcloudapi.sms.v20210111.SmsClient(credential, region);

            var req = new com.tencentcloudapi.sms.v20210111.models.DescribeSmsSignListRequest();
            req.setSignIdSet(new Long[]{0L});
            req.setInternational(0L);
            client.DescribeSmsSignList(req);

            return Map.of("status", "ok", "message", "Tencent SMS credentials validated successfully");
        } catch (com.tencentcloudapi.common.exception.TencentCloudSDKException e) {
            String code = e.getErrorCode();
            if (code != null && code.contains("AuthFailure")) {
                return Map.of("status", "error", "message", "Authentication failed: " + e.getMessage());
            }
            // Non-auth errors mean the connection succeeded
            return Map.of("status", "ok",
                    "message", "Connection successful (API returned: " + code + ")");
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Tencent SMS test failed: " + e.getMessage());
        }
    }

    private Map<String, Object> testAliyunSms(JsonNode cfg) {
        String accessKeyId = cfg.path("accessKeyId").asText("");
        String accessKeySecret = cfg.path("accessKeySecret").asText("");

        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            return Map.of("status", "error", "message", "Missing required fields: accessKeyId, accessKeySecret");
        }

        try {
            var aliConfig = new com.aliyun.teaopenapi.models.Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint("dysmsapi.aliyuncs.com");
            var client = new com.aliyun.dysmsapi20170525.Client(aliConfig);

            var req = new com.aliyun.dysmsapi20170525.models.QuerySmsSignListRequest()
                    .setPageIndex(1)
                    .setPageSize(1);
            client.querySmsSignList(req);

            return Map.of("status", "ok", "message", "Aliyun SMS credentials validated successfully");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("InvalidAccessKey") || msg.contains("SignatureDoesNotMatch"))) {
                return Map.of("status", "error", "message", "Authentication failed: " + msg);
            }
            return Map.of("status", "ok",
                    "message", "Connection successful (API returned: " + msg + ")");
        }
    }

    // -------------------------------------------------------------------------
    // EMAIL Test
    // -------------------------------------------------------------------------

    private Map<String, Object> testEmail(JsonNode cfg) {
        String host = cfg.path("host").asText("");
        int port = cfg.path("port").asInt(587);
        String username = cfg.path("username").asText("");
        String password = cfg.path("password").asText("");

        if (host.isBlank()) {
            return Map.of("status", "error", "message", "Missing required field: host");
        }

        try {
            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost(host);
            mailSender.setPort(port);
            if (!username.isBlank()) {
                mailSender.setUsername(username);
                mailSender.setPassword(password);
            }
            Properties props = mailSender.getJavaMailProperties();
            props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");

            mailSender.testConnection();
            return Map.of("status", "ok", "message", "SMTP connection successful");
        } catch (Exception e) {
            return Map.of("status", "error", "message", "SMTP test failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // OAuth Tests
    // -------------------------------------------------------------------------

    private Map<String, Object> testOAuth(String providerCode, JsonNode cfg) {
        return switch (providerCode) {
            case "google" -> testGoogleOAuth(cfg);
            case "wechat_web" -> testWeChatOAuth(cfg);
            case "apple" -> testAppleOAuth(cfg);
            default -> Map.of("status", "ok",
                    "message", "No test available for OAuth provider: " + providerCode);
        };
    }

    private Map<String, Object> testGoogleOAuth(JsonNode cfg) {
        String clientId = cfg.path("clientId").asText("");
        if (clientId.isBlank()) {
            return Map.of("status", "error", "message", "Missing required field: clientId");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://accounts.google.com/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && clientId.endsWith(".apps.googleusercontent.com")) {
                return Map.of("status", "ok", "message", "Google OAuth endpoint reachable, clientId format valid");
            }
            return Map.of("status", "ok",
                    "message", "Google OAuth endpoint reachable (status=" + response.statusCode() + ")");
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Google OAuth test failed: " + e.getMessage());
        }
    }

    private Map<String, Object> testWeChatOAuth(JsonNode cfg) {
        String appId = cfg.path("appId").asText("");
        String appSecret = cfg.path("appSecret").asText("");

        if (appId.isBlank() || appSecret.isBlank()) {
            return Map.of("status", "error", "message", "Missing required fields: appId, appSecret");
        }
        try {
            String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                    + appId + "&secret=" + appSecret;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (body.has("access_token")) {
                return Map.of("status", "ok", "message", "WeChat credentials validated successfully");
            }
            int errcode = body.path("errcode").asInt(0);
            if (errcode == 40001 || errcode == 40125) {
                return Map.of("status", "error",
                        "message", "WeChat credentials invalid: " + body.path("errmsg").asText());
            }
            return Map.of("status", "ok",
                    "message", "WeChat API reachable (errcode=" + errcode + ")");
        } catch (Exception e) {
            return Map.of("status", "error", "message", "WeChat OAuth test failed: " + e.getMessage());
        }
    }

    private Map<String, Object> testAppleOAuth(JsonNode cfg) {
        String clientId = cfg.path("clientId").asText("");
        String teamId = cfg.path("teamId").asText("");
        String keyId = cfg.path("keyId").asText("");
        String privateKey = cfg.path("privateKey").asText("");

        if (clientId.isBlank() || teamId.isBlank() || keyId.isBlank() || privateKey.isBlank()) {
            return Map.of("status", "error",
                    "message", "Missing required fields: clientId, teamId, keyId, privateKey");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://appleid.apple.com/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Map.of("status", "ok",
                        "message", "Apple Sign In endpoint reachable, all required fields present");
            }
            return Map.of("status", "error",
                    "message", "Apple endpoint returned status " + response.statusCode());
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Apple OAuth test failed: " + e.getMessage());
        }
    }
}
