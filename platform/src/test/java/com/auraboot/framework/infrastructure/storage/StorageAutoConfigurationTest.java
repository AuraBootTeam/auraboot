package com.auraboot.framework.infrastructure.storage;

import com.auraboot.framework.file.constant.StorageType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageAutoConfigurationTest {

    @Test
    void localStorageDoesNotRequireAnExternalProvider() {
        StorageProperties properties = new StorageProperties();
        ObjectProvider<StorageProvider> providers = provider(null);

        assertDoesNotThrow(() ->
                new StorageAutoConfiguration(properties, providers).validateConfiguration());
    }

    @Test
    void configuredProviderMustBeAvailable() {
        StorageProperties properties = properties("minio");

        assertThrows(IllegalStateException.class, () ->
                new StorageAutoConfiguration(properties, provider(null)).validateConfiguration());
    }

    @Test
    void configuredProviderMustMatchTheRequestedType() {
        StorageProperties properties = properties("minio");
        StorageProvider local = mock(StorageProvider.class);
        when(local.type()).thenReturn(StorageType.LOCAL);

        assertThrows(IllegalStateException.class, () ->
                new StorageAutoConfiguration(properties, provider(local)).validateConfiguration());
    }

    @Test
    void matchingConfiguredProviderIsAccepted() {
        StorageProperties properties = properties("minio");
        StorageProvider minio = mock(StorageProvider.class);
        when(minio.type()).thenReturn(StorageType.MINIO);

        assertDoesNotThrow(() ->
                new StorageAutoConfiguration(properties, provider(minio)).validateConfiguration());
    }

    private static StorageProperties properties(String type) {
        StorageProperties properties = new StorageProperties();
        properties.setType(type);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StorageProvider> provider(StorageProvider storageProvider) {
        ObjectProvider<StorageProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storageProvider);
        return provider;
    }
}
