package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Test-only opt-in startup admission. No production plugin directory or approval registration API. */
final class ProductQuoteNativePluginDirectory {
    private ProductQuoteNativePluginDirectory() {}

    static Optional<Path> select(boolean enabled, Path input, String inputSha, Path supplied, Path ownedRuntime) {
        if (!enabled) return Optional.empty();
        require(input != null && supplied != null && ownedRuntime != null && inputSha != null
                && inputSha.matches("[a-f0-9]{64}"), "PRODUCT_PLUGIN_INPUT_REQUIRED");
        try {
            require(hash(input).equals(inputSha), "PRODUCT_PLUGIN_INPUT_DRIFT");
            JsonNode value = new ObjectMapper().readTree(input.toFile());
            require(NativeProcurementWorkspace.runtime().equals(value.path("runtime").asText()), "PRODUCT_PLUGIN_RUNTIME_MISMATCH");
            Path jar = Path.of(value.path("productJarPath").asText()).toRealPath();
            Path directory = supplied.toRealPath();
            require(directory.startsWith(ownedRuntime.toRealPath()) && Files.isDirectory(directory)
                    && directory.equals(jar.getParent()), "PRODUCT_PLUGIN_DIRECTORY_MISMATCH");
            require("quote-engine-plugin-0.1.0.jar".equals(jar.getFileName().toString()), "PRODUCT_PLUGIN_ARTIFACT_MISMATCH");
            String digest = value.path("productJarSha256").asText();
            require(digest.matches("[a-f0-9]{64}") && digest.equals(hash(jar)), "PRODUCT_PLUGIN_ARTIFACT_DRIFT");
            return Optional.of(directory);
        } catch (IOException error) {
            // CATCH: expected fixture admission I/O failure; never fall back to an arbitrary/default directory.
            throw new IllegalStateException("PRODUCT_PLUGIN_ADMISSION_IO_FAILED", error);
        }
    }

    private static String hash(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }
}
