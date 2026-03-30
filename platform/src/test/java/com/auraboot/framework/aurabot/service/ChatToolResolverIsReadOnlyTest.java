package com.auraboot.framework.aurabot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatToolResolver#isReadOnly(String)}.
 * Verifies read-only classification for provider tool naming conventions.
 */
class ChatToolResolverIsReadOnlyTest {

    private ChatToolResolver resolver;

    @BeforeEach
    void setUp() {
        // Construct with null SPI ports — only isReadOnly() is tested
        resolver = new ChatToolResolver(null, null);
    }

    @Test
    void nullToolName_isReadOnly() {
        assertThat(resolver.isReadOnly(null)).isTrue();
    }

    @Test
    void nqPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("nq_some_query")).isTrue();
    }

    @Test
    void listPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("list_models")).isTrue();
    }

    @Test
    void getPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("get_record")).isTrue();
    }

    @Test
    void platformExecuteSql_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_execute_sql")).isTrue();
    }

    @Test
    void platformListModels_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_list_models")).isTrue();
    }

    @Test
    void platformModelSuggest_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_model_suggest")).isTrue();
    }

    @Test
    void platformCreateModel_isNotReadOnly() {
        assertThat(resolver.isReadOnly("platform_create_model")).isFalse();
    }

    @Test
    void cmdPrefix_isNotReadOnly() {
        assertThat(resolver.isReadOnly("cmd__crm_lead__update")).isFalse();
    }
}
