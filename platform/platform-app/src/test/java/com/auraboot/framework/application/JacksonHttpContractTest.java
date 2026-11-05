package com.auraboot.framework.application;

import com.auraboot.framework.application.config.CacheConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Servlet/auto-configuration compatibility, not a live installation or PostgreSQL test. */
class JacksonHttpContractTest {
    @Test
    void retainsShippedJackson2DefaultsAndMvcDateMoneyDslContract() {
        verifyRoundTrip(runner());
    }

    @Test
    void honorsApplicationOwnedJackson2MapperForHttpRoundTrips() {
        verifyRoundTrip(runner().withUserConfiguration(CacheConfig.class));
    }

    private WebApplicationContextRunner runner() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String[] jsonSettings = properties.entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("spring.jackson2.")
                        || entry.getKey().toString().startsWith("spring.http.converters."))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        assertThat(jsonSettings).contains("spring.http.converters.preferred-json-mapper=jackson2",
                "spring.jackson2.serialization.write-dates-as-timestamps=false");
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withPropertyValues(jsonSettings)
                .withUserConfiguration(ProbeConfiguration.class);
    }

    private void verifyRoundTrip(WebApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mapper = context.getBean(ObjectMapper.class);
            var adapter = context.getBean(RequestMappingHandlerAdapter.class);
            var jsonReader = adapter.getMessageConverters().stream()
                    .filter(converter -> converter.canRead(Payload.class, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(jsonReader).isInstanceOf(MappingJackson2HttpMessageConverter.class);
            assertThat(((MappingJackson2HttpMessageConverter) jsonReader).getObjectMapper())
                    .isSameAs(mapper);
            String input = """
                    {"amount":68000.25,"acceptedAt":"2026-11-05T08:30:00",
                     "definition":{"type":"form","children":[{"type":"text","value":"<b>客户</b>"}]},
                     "unknown":"ignored by the existing JSON contract"}
                    """;
            var result = MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(post("/compatibility-json").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isOk()).andReturn();
            JsonNode actual = mapper.readTree(result.getResponse().getContentAsByteArray());
            assertThat(actual.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("68000.25"));
            assertThat(actual.get("acceptedAt").asText()).isEqualTo("2026-11-05T08:30:00");
            assertThat(actual.get("definition")).isEqualTo(mapper.readTree(input).get("definition"));
            assertThat(actual.has("unknown")).isFalse();
        });
    }

    public record Payload(BigDecimal amount, LocalDateTime acceptedAt, JsonNode definition) { }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        Probe probe() { return new Probe(); }
    }

    @RestController
    static class Probe {
        @PostMapping("/compatibility-json")
        Payload echo(@RequestBody Payload payload) { return payload; }
    }
}
