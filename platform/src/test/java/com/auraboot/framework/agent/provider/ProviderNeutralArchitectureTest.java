package com.auraboot.framework.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed architecture gate: business/runtime production code cannot branch on
 * or embed concrete provider/model names.
 */
class ProviderNeutralArchitectureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern VENDOR_LITERAL = Pattern.compile(
            "\"(?:qwen|deepseek|claude|anthropic|openai|gpt-|ollama)[^\"\\n]*\"",
            Pattern.CASE_INSENSITIVE);

    @Test
    void providerNamesStayInsideAdaptersAndCatalogs() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(main)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !allowed(path))
                    .forEach(path -> inspect(path, violations));
        }
        assertThat(violations)
                .as("provider/model literals belong only in adapters and provider catalogs")
                .isEmpty();
    }

    @Test
    void agentControlPlaneFormsKeepAValidProviderNeutralModelAndVisibilityContract()
            throws IOException {
        Path config = Path.of("../plugins/agent-control-plane/config");
        JsonNode dicts = MAPPER.readTree(config.resolve("dicts.json").toFile());
        JsonNode modelDict = findByCode(dicts, "acp_ai_model");
        assertThat(modelDict.path("items")).hasSize(1);
        assertThat(modelDict.path("items").get(0).path("value").asText())
                .isEqualTo("provider-default");

        JsonNode fields = MAPPER.readTree(config.resolve("fields.json").toFile());
        JsonNode visibility = findByCode(fields, "visibility");
        assertThat(visibility.path("dictCode").asText()).isEqualTo("acp_agent_visibility");

        JsonNode bindings = MAPPER.readTree(config.resolve("bindings.json").toFile());
        assertThat(bindings).anyMatch(binding ->
                "agent_definition".equals(binding.path("modelCode").asText())
                        && "visibility".equals(binding.path("fieldCode").asText()));

        JsonNode commands = MAPPER.readTree(config.resolve("commands.json").toFile());
        for (String commandCode : List.of(
                "acp:create_agent_definition", "acp:update_agent_definition")) {
            assertThat(findByCode(commands, commandCode).path("inputFields"))
                    .anyMatch(field -> "visibility".equals(field.asText()));
        }
    }

    private static JsonNode findByCode(JsonNode array, String code) {
        for (JsonNode item : array) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing code: " + code);
    }

    private static boolean allowed(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/agent/provider/")
                || normalized.endsWith("/chatbi/v2/provider/OpenAiLlmProvider.java")
                || normalized.endsWith("/chatbi/v2/provider/AnthropicLlmProvider.java")
                || normalized.endsWith("/application/bootstrap/seeder/CloudConfigSeeder.java")
                || normalized.endsWith("/observability/GenAiPricing.java");
    }

    private static void inspect(Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("//")
                        || trimmed.startsWith("*")
                        || trimmed.startsWith("/*")) {
                    continue;
                }
                if (VENDOR_LITERAL.matcher(lines.get(i)).find()) {
                    violations.add(path + ":" + (i + 1) + " " + trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
