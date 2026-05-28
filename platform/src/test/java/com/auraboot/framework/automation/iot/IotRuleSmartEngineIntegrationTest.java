package com.auraboot.framework.automation.iot;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.bpm.AutomationProcessRuntime;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end spike (ROI #5) — proves that the four IoT rule nodes
 * (filter / enrichment / transformation / action) run on the existing
 * AuraBoot automation + SmartEngine stack without any engine-level
 * extension. Drives the sample rule from
 * {@code src/test/resources/automation/iot/temp-alarm-rule.json}.
 */
@Slf4j
@DisplayName("IoT rule nodes on SmartEngine (spike)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class IotRuleSmartEngineIntegrationTest extends BaseIntegrationTest {

    static final List<Map<String, Object>> CAPTURED_ENVELOPES = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class SpikeConfig {
        @Bean
        IotActionSink recordingSink() {
            return (kind, envelope) -> CAPTURED_ENVELOPES.add(Map.copyOf(envelope));
        }

        @Bean
        BackgroundDeviceAccessor stubDeviceAccessor() {
            // Test fixture: device dev-001 lives on the furnace product at site Plant-7.
            return deviceId -> "dev-001".equals(deviceId)
                    ? Map.of("productId", "prod-furnace", "site", "Plant-7", "firmware", "1.4.2")
                    : null;
        }

        @Bean
        BackgroundProductAccessor stubProductAccessor() {
            return productId -> "prod-furnace".equals(productId)
                    ? Map.of("model", "FX-200", "thresholdC", 80.0, "gbt", "GB/T-12345")
                    : null;
        }
    }

    @Autowired
    private AutomationProcessRuntime runtime;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearSink() {
        CAPTURED_ENVELOPES.clear();
    }

    private Automation buildRuleAutomation(String pidSuffix) throws Exception {
        Map<String, Object> flowConfig;
        try (InputStream in = new ClassPathResource("automation/iot/temp-alarm-rule.json")
                .getInputStream()) {
            flowConfig = objectMapper.readValue(in, new TypeReference<>() {});
        }
        // The "_doc" prose key is for humans, not the compiler; strip it.
        flowConfig.remove("_doc");

        Automation a = new Automation();
        a.setPid("ITIOT" + pidSuffix + System.currentTimeMillis());
        a.setName("IoT temperature alarm rule (spike)");
        a.setTenantId(MetaContext.getCurrentTenantId());
        a.setFlowConfig(flowConfig);
        a.setEnabled(true);
        return a;
    }

    @Test
    @DisplayName("hot telemetry from in-scope device emits an alarm envelope")
    void hotTelemetry_emitsAlarm() throws Exception {
        Automation rule = buildRuleAutomation("HOT");
        runtime.deploy(rule);

        Map<String, Object> telemetry = new HashMap<>();
        telemetry.put(IotRuleContextKeys.DEVICE_ID, "dev-001");
        telemetry.put(IotRuleContextKeys.TENANT_ID, MetaContext.getCurrentTenantId());
        telemetry.put("temperatureF", 200.0); // ~93.3 °C → above 80
        telemetry.put(IotRuleContextKeys.TELEMETRY, Map.of("raw", "ok"));

        runtime.run(rule, "telemetry-1", telemetry);

        assertThat(CAPTURED_ENVELOPES)
                .as("the alarm action node should fan one envelope to the sink")
                .hasSize(1);
        Map<String, Object> env = CAPTURED_ENVELOPES.get(0);
        assertThat(env).containsEntry("kind", "alarm");
        assertThat(env).containsEntry("topic", "iot.alarm.v1");
        assertThat(env).containsEntry("deviceId", "dev-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload).containsEntry("metric", "temperature");
        assertThat(payload).containsEntry("severity", "MAJOR");
        assertThat(payload).containsEntry("site", "Plant-7"); // came from enrichment
        // value type preserved: ${tempC} is a Double, not its toString
        Object valueC = payload.get("valueC");
        assertThat(valueC).isInstanceOf(Number.class);
        assertThat(((Number) valueC).doubleValue()).isGreaterThan(80.0);
    }

    @Test
    @DisplayName("cool telemetry below threshold does not emit an alarm")
    void coolTelemetry_noAlarm() throws Exception {
        Automation rule = buildRuleAutomation("COOL");
        runtime.deploy(rule);

        Map<String, Object> telemetry = new HashMap<>();
        telemetry.put(IotRuleContextKeys.DEVICE_ID, "dev-001");
        telemetry.put(IotRuleContextKeys.TENANT_ID, MetaContext.getCurrentTenantId());
        telemetry.put("temperatureF", 100.0); // ~37.8 °C, well below 80

        runtime.run(rule, "telemetry-2", telemetry);

        assertThat(CAPTURED_ENVELOPES)
                .as("alarm node sits behind the high-temp gateway; it must not fire")
                .isEmpty();
    }

    @Test
    @DisplayName("telemetry from out-of-scope product is dropped before enrichment")
    void outOfScopeProduct_dropped() throws Exception {
        Automation rule = buildRuleAutomation("DROP");
        runtime.deploy(rule);

        Map<String, Object> telemetry = new HashMap<>();
        telemetry.put(IotRuleContextKeys.DEVICE_ID, "dev-other");
        telemetry.put(IotRuleContextKeys.PRODUCT_ID, "prod-other");
        telemetry.put(IotRuleContextKeys.TENANT_ID, MetaContext.getCurrentTenantId());
        telemetry.put("temperatureF", 999.0); // would normally trip the threshold

        runtime.run(rule, "telemetry-3", telemetry);

        assertThat(CAPTURED_ENVELOPES)
                .as("the filter must short-circuit the run before the alarm node")
                .isEmpty();
    }
}
