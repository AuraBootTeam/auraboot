package com.auraboot.framework.iot.broker;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EMQX 5 rule-engine <b>dry-run</b> client — wraps {@code POST /api/v5/rule_test}.
 *
 * <p>This is the faithful evaluation primitive for {@code rule_simulate}: it
 * hands EMQX a single message context (topic + payload) and the rule SQL, and
 * EMQX's own rule engine evaluates the {@code FROM}/{@code WHERE} synchronously —
 * no rule is provisioned, nothing is published to a live topic, and no
 * Kafka/BPM is touched. Per the verified EMQX 5.8 contract:
 * <ul>
 *   <li>{@code HTTP 200} + the SELECT projection → the SQL <b>matched</b>.</li>
 *   <li>{@code HTTP 412} {@code {"code":"NOT_MATCH"}} → not matched.</li>
 *   <li>{@code HTTP 400} → the rule SQL is invalid.</li>
 * </ul>
 *
 * <p>When {@code iot.emqx.enabled=false} (default for OSS/unit profiles) every
 * call throws — SQL rules cannot be faithfully evaluated without the broker, and
 * we never fabricate a result (§8).
 *
 * @since 2.6.0
 */
@Slf4j
@Service
@EnableConfigurationProperties(EmqxAclProperties.class)
public class EmqxRuleTestService {

    private static final String RULE_TEST_PATH = "/api/v5/rule_test";

    private final EmqxAclProperties props;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public EmqxRuleTestService(EmqxAclProperties props,
                               ObjectMapper objectMapper,
                               WebClient.Builder webClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.webClient = buildClient(webClientBuilder);
    }

    private WebClient buildClient(WebClient.Builder builder) {
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            return builder.build();
        }
        String creds = (props.getApiKey() == null ? "" : props.getApiKey())
                + ":" + (props.getApiSecret() == null ? "" : props.getApiSecret());
        String basic = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        return builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * Evaluate a single message against the rule SQL via EMQX's dry-run endpoint.
     *
     * @param sql         the stored EMQX rule SQL ({@code iot_r_expression})
     * @param topic       the MQTT topic the simulated frame is published on (must
     *                    satisfy the SQL's {@code FROM} filter)
     * @param payloadJson the reconstructed telemetry payload as a JSON string
     * @return {@code true} when the SQL matched (would fire), {@code false} on
     *         {@code NOT_MATCH}
     * @throws MetaServiceException {@code iot.error.emqx_disabled_rule_simulate}
     *         when the broker is disabled, {@code iot.error.rule_sql_invalid} on
     *         a 400, or {@code iot.error.emqx_rule_test} on any other failure.
     */
    public boolean matches(String sql, String topic, String payloadJson) {
        if (!props.isEnabled()) {
            throw new MetaServiceException("iot.error.emqx_disabled_rule_simulate");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("event_type", "message_publish");
        context.put("topic", topic);
        context.put("payload", payloadJson);
        context.put("qos", 1);
        context.put("clientid", "iot-rule-sim");
        context.put("username", "iot-rule-sim");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        body.put("context", context);

        try {
            webClient
                    .method(HttpMethod.POST)
                    .uri(URI.create(props.getBaseUrl() + RULE_TEST_PATH))
                    .bodyValue(toJson(body))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(props.getTimeoutMs()));
            return true;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 412) {
                // EMQX {"code":"NOT_MATCH"} — the SQL FROM/WHERE did not match.
                return false;
            }
            if (status == 400) {
                throw new MetaServiceException(
                        "iot.error.rule_sql_invalid status=400 body=" + truncate(e.getResponseBodyAsString()), e);
            }
            throw new MetaServiceException("iot.error.emqx_rule_test status=" + status, e);
        } catch (RuntimeException e) {
            throw new MetaServiceException("iot.error.emqx_rule_test error=" + e, e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new MetaServiceException("iot.error.emqx_rule_test_payload_encode_failed", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }
}
