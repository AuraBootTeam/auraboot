package com.auraboot.framework.branding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthAppearanceAssetServiceTest {
    @TempDir Path directory;
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jvXcAAAAASUVORK5CYII=");
    private AuthAppearanceAssetService service(boolean licensed) {
        DeploymentBrandingProvider provider = mock(DeploymentBrandingProvider.class);
        when(provider.isCommercialBrandingEnabled()).thenReturn(licensed);
        return new AuthAppearanceAssetService(new MockEnvironment().withProperty(
                "AURABOOT_AUTH_APPEARANCE_ASSET_ROOT", directory.toString()), provider);
    }
    @Test void uploadRetainsImmutableContentAndIgnoresUntrustedFilename() throws Exception {
        var service = service(true);
        var file = new MockMultipartFile("file", "../../outside.html", "text/html", PNG);
        var asset = service.upload(file);
        assertThat(service.upload(file)).isEqualTo(asset);
        String name = asset.url().substring(asset.url().lastIndexOf('/') + 1);
        assertThat(name).matches("[a-f0-9]{64}\\.png");
        assertThat(Files.readAllBytes(service.read(name))).isEqualTo(PNG);
        try (var files = Files.list(directory)) { assertThat(files.count()).isEqualTo(1); }
    }
    @Test void rejectsActiveContentEmptyAndOversizedFiles() {
        var service = service(true);
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "x.png", "image/png", "<svg onload='alert(1)'/>".getBytes())))
                .hasMessageContaining("assetType");
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", new byte[0])))
                .hasMessageContaining("assetSize");
        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1])))
                .hasMessageContaining("assetSize");
    }
    @Test void rejectsCommunityUploadAndPathTraversal() {
        assertThatThrownBy(() -> service(false).upload(new MockMultipartFile("file", PNG)))
                .hasMessageContaining("commercialRequired");
        assertThatThrownBy(() -> service(true).read("../outside.png"))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }
    @Test void rejectsSymlinkEvenWhenItsBytesMatch() throws Exception {
        var service = service(true);
        var file = new MockMultipartFile("file", PNG);
        String url = service.upload(file).url();
        Path asset = directory.resolve(url.substring(url.lastIndexOf('/') + 1));
        Path other = directory.resolve("other");
        Files.move(asset, other);
        Files.createSymbolicLink(asset, other);
        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(IllegalStateException.class);
    }
}
