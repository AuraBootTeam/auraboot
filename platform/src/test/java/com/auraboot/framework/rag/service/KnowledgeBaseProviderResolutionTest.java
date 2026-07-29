package com.auraboot.framework.rag.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A knowledge base must not be created on an embedding provider this deployment cannot use.
 *
 * The old default was the literal "openai". EmbeddingService already auto-resolves the
 * first enabled provider — but only when the requested code is blank (#1390 F3) — and the
 * create dialog always sent a non-blank 'openai'. The fallback therefore never ran, the
 * lookup went to a provider with no credentials, and every chunk failed to embed. The
 * downstream reporting was honest about it (a red 0/N badge, path=keyword), which made it
 * survivable but no less of a dead end: the default is what walked the user into it.
 *
 * Both directions are pinned: blank resolves, and an explicitly named provider that is
 * not enabled is refused up front rather than baked into a knowledge base that can never
 * embed anything.
 */
class KnowledgeBaseProviderResolutionTest {

    private static final long TENANT = 7L;

    private static CloudConfig provider(String code, String configJson) {
        CloudConfig cc = new CloudConfig();
        cc.setProviderCode(code);
        cc.setConfig(configJson);
        return cc;
    }

    /** The service has many collaborators; only the two this path uses are supplied. */
    private KnowledgeBaseService serviceWith(List<CloudConfig> enabled) {
        CloudConfigService cloud = mock(CloudConfigService.class);
        when(cloud.getEnabledProviders(anyLong(), eq("embedding"))).thenReturn(enabled);
        KnowledgeBaseService svc = new KnowledgeBaseService(
                null, null, null, null, cloud, new ObjectMapper(), null);
        ReflectionTestUtils.setField(svc, "cloudConfigService", cloud);
        return svc;
    }

    @Test
    void a_blank_provider_resolves_to_what_the_deployment_has_enabled() {
        var svc = serviceWith(List.of(
                provider("qianwen", "{\"defaultModel\":\"text-embedding-v4\",\"dimensions\":1536}")));

        var resolved = svc.resolveEmbeddingProvider(TENANT, "");

        assertThat(resolved.provider())
                .as("blank must not silently become 'openai' — that is the bug")
                .isEqualTo("qianwen");
        assertThat(resolved.model())
                .as("the provider's own default model, or the first embed says 'model not found'")
                .isEqualTo("text-embedding-v4");
    }

    @Test
    void null_is_treated_the_same_as_blank() {
        var svc = serviceWith(List.of(provider("qianwen", "{\"defaultModel\":\"text-embedding-v4\"}")));
        assertThat(svc.resolveEmbeddingProvider(TENANT, null).provider()).isEqualTo("qianwen");
    }

    @Test
    void an_explicitly_named_provider_that_is_enabled_is_honoured() {
        var svc = serviceWith(List.of(
                provider("qianwen", "{\"defaultModel\":\"text-embedding-v4\"}"),
                provider("openai", "{\"defaultModel\":\"text-embedding-3-small\"}")));

        var resolved = svc.resolveEmbeddingProvider(TENANT, "openai");

        assertThat(resolved.provider()).isEqualTo("openai");
        assertThat(resolved.model()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void an_explicitly_named_provider_that_is_NOT_enabled_is_refused() {
        // The exact case the dialog used to produce: only qianwen is enabled, the form
        // sends 'openai'. Failing here costs one error message; failing later costs a
        // knowledge base that looks fine and answers nothing.
        var svc = serviceWith(List.of(provider("qianwen", "{\"defaultModel\":\"text-embedding-v4\"}")));

        assertThatThrownBy(() -> svc.resolveEmbeddingProvider(TENANT, "openai"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("not enabled")
                .as("the message must name what IS available, or the user cannot act on it")
                .hasMessageContaining("qianwen");
    }

    @Test
    void no_provider_configured_at_all_is_refused_with_a_distinct_message() {
        var svc = serviceWith(List.of());

        assertThatThrownBy(() -> svc.resolveEmbeddingProvider(TENANT, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No embedding provider");
    }

    @Test
    void a_malformed_config_blob_is_refused_before_creating_a_dead_knowledge_base() {
        var svc = serviceWith(List.of(provider("qianwen", "{not json")));

        assertThatThrownBy(() -> svc.resolveEmbeddingProvider(TENANT, "qianwen"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("defaultModel");
    }
}
