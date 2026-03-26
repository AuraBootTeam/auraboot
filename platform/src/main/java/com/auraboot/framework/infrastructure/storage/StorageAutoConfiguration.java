package com.auraboot.framework.infrastructure.storage;

import com.auraboot.framework.infrastructure.storage.local.LocalStorageProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the {@link StorageProvider} SPI.
 * <p>
 * Activation rules:
 * <ul>
 *   <li>{@code aura.storage.type=minio} &rarr; add platform-storage-minio module</li>
 *   <li>{@code aura.storage.type=s3}    &rarr; add platform-storage-s3 module</li>
 *   <li>{@code aura.storage.type=oss}   &rarr; add platform-storage-oss module</li>
 *   <li>{@code aura.storage.type=local} (default) &rarr; LocalStorageProvider</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    private final StorageProperties properties;
    private final ObjectProvider<StorageProvider> providerHolder;

    public StorageAutoConfiguration(StorageProperties properties,
                                     ObjectProvider<StorageProvider> providerHolder) {
        this.properties = properties;
        this.providerHolder = providerHolder;
    }

    @Bean
    @ConditionalOnMissingBean(StorageProvider.class)
    public StorageProvider localStorageProvider(StorageProperties props) {
        return new LocalStorageProvider(props);
    }

    @PostConstruct
    public void validateConfiguration() {
        String type = properties.getType();
        if (!"local".equals(type)) {
            StorageProvider provider = providerHolder.getIfAvailable();
            if (provider == null || "local".equals(provider.type().name())) {
                log.warn("aura.storage.type={} is configured but no matching provider module found. "
                        + "Add 'platform-storage-{}' to your dependencies. Falling back to LocalStorageProvider.", type, type);
            } else {
                log.info("StorageProvider activated: type={}, provider={}", type, provider.getClass().getSimpleName());
            }
        }
    }
}
