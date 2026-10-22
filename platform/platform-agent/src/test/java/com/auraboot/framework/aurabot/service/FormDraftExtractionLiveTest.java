package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.*;
import com.auraboot.framework.agent.util.LiveLlmSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Opt-in semantic evaluation: seven single-call cases, no business tools or writes. */
@Tag("agent-eval-live")
class FormDraftExtractionLiveTest {
    @Test
    @Timeout(180)
    @SuppressWarnings("unchecked")
    void sourceGroundedDraftExtractionMatchesTheVersionedBusinessCorpus() throws Exception {
        var profile = LiveLlmSeeder.resolve();
        assertThat(profile).withFailMessage(LiveLlmSeeder.skipReason()).isNotNull();
        assertThat(profile.baseUrl()).withFailMessage("AURA_LIVE_LLM_BASE_URL is required").isNotBlank();
        String format = System.getenv("AURA_LIVE_LLM_API_FORMAT");
        assertThat(format).isIn("messages", "chat_completions");
        var mapper = new ObjectMapper();
        var corpus = mapper.readTree(getClass().getResourceAsStream("/agent/form-draft-extraction-cases.json"));
        var form = mapper.treeToValue(corpus.path("form"), FormFillRequest.class);
        String seed = Files.readString(Path.of("../scripts/seed-form-fill-skill.sql"));
        String prompt = seed.split("\\$playbook\\$")[1];
        var registry = new SimpleMeterRegistry();
        LlmProvider provider = "messages".equals(format)
                ? new AnthropicLlmProvider(WebClient.create(), mapper, registry)
                : new OpenAiCompatibleLlmProvider(WebClient.create(), mapper);
        var results = new ArrayList<Map<String, Object>>();
        try {
            for (var scenario : corpus.path("cases")) {
                String source = scenario.path("source").asText();
                var request = LlmChatRequest.builder().model(profile.model()).maxTokens(1536)
                        .systemPrompt(prompt + "\ncurrentDate=" + form.currentDate() + "; timeZone=" + form.timeZone())
                        .messages(List.of(LlmChatRequest.Message.builder().role("user").content(source).build()))
                        .tools(new ChatToolResolver(null, null, null).resolveFormFill(form).tools()).build();
                var response = provider.chat(request, profile.apiKey(), profile.baseUrl());
                var calls = response.getContent().stream().filter(c -> "tool_use".equals(c.getType())).toList();
                assertThat(calls).as(scenario.path("id").asText()).hasSize(1);
                var call = calls.getFirst();
                assertThat(call.getName()).isEqualTo("platform_fill_form");
                FormFillContract.validate(FormFillContract.schema(form), call.getInput(), source);
                assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(call.getInput().get("fields")))
                        .as("%s fields", scenario.path("id").asText()).isEqualTo(scenario.path("expected"));
                Map<String, Map<String, Object>> reviews = (Map<String, Map<String, Object>>) call.getInput().get("reviews");
                var actualAmbiguous = new java.util.LinkedHashMap<String, Object>();
                reviews.forEach((code, review) -> {
                    if ("ambiguous".equals(review.get("status"))) actualAmbiguous.put(code, review.get("reason"));
                });
                assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(actualAmbiguous))
                        .as("%s ambiguities", scenario.path("id").asText()).isEqualTo(scenario.path("ambiguities"));
                results.add(Map.of("case", scenario.path("id").asText(), "passed", true,
                        "fields", call.getInput().get("fields"), "reviews", reviews));
            }
        } finally {
            registry.close();
            Path report = Path.of("build/reports/form-draft-live.json");
            Files.createDirectories(report.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                    "provider", profile.providerCode(), "model", profile.model(), "api_format", format,
                    "planned_cases", corpus.path("cases").size(), "completed_cases", results.size(), "cases", results));
        }
    }
}
