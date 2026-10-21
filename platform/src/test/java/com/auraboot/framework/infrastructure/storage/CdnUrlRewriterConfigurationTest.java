package com.auraboot.framework.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class CdnUrlRewriterConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void missingCdnUrlLeavesAuthenticatedDownloadHandlingInPlace() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CdnUrlRewriter.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankCdnUrlDoesNotCreateARewriter(String value) {
        runner.withPropertyValues("aura.storage.cdn.base-url=" + value)
                .run(context -> assertThat(context).doesNotHaveBean(CdnUrlRewriter.class));
    }

    @Test
    void explicitlyConfiguredCdnStillRewritesStorageKeys() {
        runner.withPropertyValues("aura.storage.cdn.base-url=https://cdn.example.com/")
                .run(context -> {
                    assertThat(context).hasSingleBean(CdnUrlRewriter.class);
                    assertThat(context.getBean(CdnUrlRewriter.class).rewrite("folder/key.png"))
                            .isEqualTo("https://cdn.example.com/folder/key.png");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    @Import(CdnUrlRewriter.class)
    static class Config {}
}
