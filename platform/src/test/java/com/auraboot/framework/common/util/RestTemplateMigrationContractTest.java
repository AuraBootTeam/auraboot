package com.auraboot.framework.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the 2026-04-18 RestTemplate→pinned-IP HttpClient migration
 * (P3-E DNS rebinding follow-up).
 *
 * <p>Each of the 6 migrated call sites (WebhookDispatcherImpl,
 * ApiConnectorServiceImpl, GmailApiClient, DefaultLlmClient,
 * EndpointModelExecutor, HttpServiceTaskDelegate) now carries a static
 * {@link HttpClient} field named {@code PINNED_HTTP_CLIENT} that is the single
 * entry point for outbound HTTP. This test guards against silent regressions
 * (someone reintroducing a {@code RestTemplate} field) by asserting the shape
 * of those classes reflectively.
 *
 * <p>Combined with the per-site SSRF negative-path tests, this provides a
 * bright-line signal that the pinned-IP migration has not been undone.
 */
class RestTemplateMigrationContractTest {

    private static final List<String> MIGRATED_CLASSES = List.of(
            "com.auraboot.framework.webhook.service.impl.WebhookDispatcherImpl",
            "com.auraboot.framework.connector.service.impl.ApiConnectorServiceImpl",
            "com.auraboot.framework.email.service.GmailApiClient",
            "com.auraboot.framework.intent.service.DefaultLlmClient",
            "com.auraboot.framework.meta.service.executor.EndpointModelExecutor",
            "com.auraboot.framework.bpm.chain.HttpServiceTaskDelegate"
    );

    @Test
    @DisplayName("No migrated class retains a RestTemplate field (regression guard)")
    void migratedClasses_doNotUseRestTemplate() throws Exception {
        for (String className : MIGRATED_CLASSES) {
            Class<?> clazz = Class.forName(className);
            for (Field f : clazz.getDeclaredFields()) {
                assertThat(f.getType().getName())
                        .as("Field %s in %s must not be a RestTemplate",
                                f.getName(), className)
                        .isNotEqualTo("org.springframework.web.client.RestTemplate");
            }
        }
    }

    @Test
    @DisplayName("All migrated classes expose a pinned JDK HttpClient instance")
    void migratedClasses_exposePinnedHttpClient() throws Exception {
        for (String className : MIGRATED_CLASSES) {
            Class<?> clazz = Class.forName(className);
            boolean hasHttpClient = false;
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == HttpClient.class) {
                    hasHttpClient = true;
                    break;
                }
            }
            assertThat(hasHttpClient)
                    .as("%s must declare a java.net.http.HttpClient field "
                            + "(pinned-IP outbound HTTP path)", className)
                    .isTrue();
        }
    }
}
