package com.auraboot.framework.notification.sourcebinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic startup-directory admission only; fixture JAR bytes are not a real Product/provider proof. */
class ProductQuoteNativePluginDirectoryTest {
    @TempDir Path root;
    @Test void disabledNeedsNoInputAndDoesNotSelectAPluginDirectory() {
        assertThat(ProductQuoteNativePluginDirectory.select(false, null, null, null, null)).isEmpty();
    }
    @Test void selectedFacetRejectsMissingDirectoryWithoutFallback() {
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, null, null, null, root))
                .isInstanceOf(IllegalStateException.class).hasMessage("PRODUCT_PLUGIN_INPUT_REQUIRED");
    }
    @Test void exactOwnedDirectoryAndSealedArtifactAreSelected() throws Exception {
        Fixture f = fixture();
        assertThat(ProductQuoteNativePluginDirectory.select(true, f.input, hash(f.input), f.directory, root))
                .contains(f.directory.toRealPath());
    }
    @Test void inputDirectoryScopeAndJarByteDriftEachFailClosed() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, f.input, "0".repeat(64), f.directory, root))
                .hasMessage("PRODUCT_PLUGIN_INPUT_DRIFT");
        Path wrong = Files.createDirectory(root.resolve("wrong"));
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, f.input, hash(f.input), wrong, root))
                .hasMessage("PRODUCT_PLUGIN_DIRECTORY_MISMATCH");
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, f.input, hash(f.input), f.directory, wrong))
                .hasMessage("PRODUCT_PLUGIN_DIRECTORY_MISMATCH");
        Files.writeString(f.jar, "changed");
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, f.input, hash(f.input), f.directory, root))
                .hasMessage("PRODUCT_PLUGIN_ARTIFACT_DRIFT");
    }
    @Test void aSealedArtifactForTheOtherAllowedRuntimeIsStillRejected() throws Exception {
        Fixture f = fixture();
        var json = new ObjectMapper();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(f.input.toFile());
        input.put("runtime", NativeProcurementWorkspace.runtime().equals("procurement-main-validation")
                ? "procurement-web-stack-20261010" : "procurement-main-validation");
        json.writeValue(f.input.toFile(), input);
        assertThatThrownBy(() -> ProductQuoteNativePluginDirectory.select(true, f.input, hash(f.input), f.directory, root))
                .hasMessage("PRODUCT_PLUGIN_RUNTIME_MISMATCH");
    }
    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(root.resolve("plugins"));
        Path jar = directory.resolve("quote-engine-plugin-0.1.0.jar");
        Files.writeString(jar, "hermetic admission bytes only");
        Path input = root.resolve("input.json");
        new ObjectMapper().writeValue(input.toFile(), Map.of("runtime", NativeProcurementWorkspace.runtime(),
                "productJarPath", jar.toString(), "productJarSha256", hash(jar)));
        return new Fixture(directory, jar, input);
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private record Fixture(Path directory, Path jar, Path input) {}
}
