package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.local.LocalStorageProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExportArtifactStoreTest {
    @TempDir Path temporary;
    private Path storage;
    private ExportArtifactStore artifacts;
    @BeforeEach void configure() {
        storage=temporary.resolve("private-objects");
        var properties=new StorageProperties();properties.getLocal().setBasePath(storage.toString());
        artifacts=new ExportArtifactStore(new LocalStorageProvider(properties));
    }
    @Test void verifiedDownloadRetainsTheExactPrivateCsvBytesWithoutReturningAStorageUrl() throws Exception {
        byte[] bytes="\uFEFFtitle,name\r\nNotice,\"Name with, comma\"\r\n".getBytes(StandardCharsets.UTF_8);
        Path source=temporary.resolve("prepared.csv");Files.write(source,bytes);
        var stored=artifacts.upload(source,"exports/1/2/private/claim.csv");
        assertThat(stored.size()).isEqualTo(bytes.length);assertThat(stored.checksum()).matches("[0-9a-f]{64}");
        Files.writeString(source,"changed after upload");
        var download=artifacts.verified(stored.key(),stored.size(),stored.checksum());
        try(var content=download.content()) {assertThat(content.readAllBytes()).containsExactly(bytes);}
        assertThat(storage.resolve(stored.key())).exists();
    }
    @Test void sameLengthObjectTamperingIsRejectedBeforeAnyDownloadStreamIsReturned() throws Exception {
        Path source=temporary.resolve("prepared.csv");Files.writeString(source,"original");
        var stored=artifacts.upload(source,"exports/1/2/private/claim.csv");
        Files.writeString(storage.resolve(stored.key()),"modified");
        assertThatThrownBy(()->artifacts.verified(stored.key(),stored.size(),stored.checksum()))
            .isInstanceOf(com.auraboot.framework.exception.BusinessException.class).hasMessage("$i18n:export.artifact_integrity");
    }
    @Test void truncatedObjectsAndSymbolicTemporarySourcesCannotBecomeCompletedArtifacts() throws Exception {
        Path source=temporary.resolve("prepared.csv");Files.writeString(source,"original");
        var stored=artifacts.upload(source,"exports/1/2/private/claim.csv");
        Files.writeString(storage.resolve(stored.key()),"short");
        assertThatThrownBy(()->artifacts.verified(stored.key(),stored.size(),stored.checksum()))
            .isInstanceOf(com.auraboot.framework.exception.BusinessException.class).hasMessage("$i18n:export.artifact_integrity");
        Path symbolic=temporary.resolve("symbolic.csv");Files.createSymbolicLink(symbolic,source);
        assertThatThrownBy(()->artifacts.upload(symbolic,"exports/1/2/private/unsafe.csv")).isInstanceOf(java.io.IOException.class);
        assertThat(storage.resolve("exports/1/2/private/unsafe.csv")).doesNotExist();
    }
}
