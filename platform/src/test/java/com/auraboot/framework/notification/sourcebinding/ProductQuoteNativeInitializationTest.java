package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic appendix authority checks; no native command execution is claimed. */
class ProductQuoteNativeInitializationTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    private Path original() throws Exception {
        Path source = Files.createDirectory(root.resolve("original"));
        Files.createDirectories(source.resolve("config/fields"));
        Files.createDirectories(source.resolve("config/commands"));
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.quote-procurement\"}");
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:sync_directory\"],\"defaultValue\":\"inactive\"}");
        Files.writeString(source.resolve("config/fields/unrelated.json"), "{\"code\":\"other_protected\",\"allowedWriterCommands\":[]}");
        Files.writeString(source.resolve("config/commands/formal.json"), "[{\"code\":\"formal-command\"}]");
        return source;
    }

    @Test void appendixPreservesProductionWritersCommandsAndOriginalBytes() throws Exception {
        Path source = original();
        String original = Files.readString(source.resolve("config/fields/contact.json"));
        Path copy = root.resolve("appendix");
        var proof = ProductQuoteNativeInitialization.prepare(source, copy, json);
        assertThat(Files.readString(source.resolve("config/fields/contact.json"))).isEqualTo(original);
        var field = json.readTree(copy.resolve("config/fields/contact.json").toFile());
        assertThat(field.path("allowedWriterCommands").toString()).isEqualTo(
                "[\"pr_supplier_pcba_contact:sync_directory\",\"pr_supplier_pcba_contact:initialize_s06_native_fixture\"]");
        assertThat(field.path("defaultValue").asText()).isEqualTo("inactive");
        assertThat(Files.readAllBytes(copy.resolve("config/commands/formal.json"))).isEqualTo(Files.readAllBytes(source.resolve("config/commands/formal.json")));
        assertThat(Files.readAllBytes(copy.resolve("config/fields/unrelated.json"))).isEqualTo(Files.readAllBytes(source.resolve("config/fields/unrelated.json")));
        var commands = json.readTree(copy.resolve("config/commands/s06-native-initialization.json").toFile());
        assertThat(commands).hasSize(5);
        for (var command : commands) {
            assertThat(command.path("type").asText()).isEqualTo("create");
            assertThat(command.path("permissions").toString()).isEqualTo("[\"qo.procurement.integrate\"]");
            assertThat(command.path("code").asText()).isEqualTo(ProductQuoteNativeInitialization.command(command.path("modelCode").asText()));
        }
        assertThat(proof).containsKeys("sourceResourceHashes", "exactWriterAppendix", "commands");
    }

    @Test void missingExtraFieldsUnsupportedModelAndReusedDirectoryCannotAcquireInitialization() throws Exception {
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validatePayload("pr_supplier_common", Map.of("pr_supplier_code", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validatePayload("pr_supplier_common", Map.of(
                "pr_supplier_code", "x", "pr_supplier_name", "x", "pr_supplier_status", "enabled", "extra", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.command("unknown_model")).isInstanceOf(IllegalArgumentException.class);
        Path source = original();
        Path copy = Files.createDirectory(root.resolve("used"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, copy, json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void malformedWriterDeclarationAndPreexistingAppendixFailClosed() throws Exception {
        Path source = original();
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":\"ALL\"}");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, root.resolve("malformed"), json)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:initialize_s06_native_fixture\"]}");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, root.resolve("already"), json)).isInstanceOf(IllegalArgumentException.class);
    }
}
