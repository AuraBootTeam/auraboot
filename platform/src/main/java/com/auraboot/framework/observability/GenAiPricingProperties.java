package com.auraboot.framework.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment-configured LLM rates, merged over {@link GenAiPricing}'s built-in
 * table (longest prefix wins across both).
 *
 * <p>Exists because the built-in table can only carry public list prices for the
 * handful of models it knows, while a real deployment (a) uses vendors the table
 * has never heard of and (b) pays contracted rates that are not the list price. The
 * observed consequence of having no such channel: 51 ledger rows for
 * {@code qwen-plus} carrying 43k real tokens, every one of them priced at $0.
 *
 * <p>Rates are USD per 1,000,000 tokens. Prefixes are matched case-insensitively
 * against the model id, so {@code qwen-plus} covers {@code qwen-plus-2026-01-01}.
 *
 * <pre>{@code
 * auraboot:
 *   genai:
 *     pricing:
 *       models:
 *         qwen-plus:
 *           input: "0.40"
 *           output: "1.20"
 *         qwen-turbo:
 *           input: "0.05"
 *           output: "0.20"
 *         deepseek-v4-flash:
 *           input: "0.10"
 *           output: "0.40"
 *           cache-read: "0.02"
 * }</pre>
 *
 * <p>Quote the values in YAML so each rate is parsed as an exact
 * {@link BigDecimal} and never as a binary float.
 *
 * <p>Plain accessors rather than Lombok: this class is pure configuration data with
 * no Spring context needed to exercise it, and keeping it processor-free means the
 * pricing unit tests compile and run without the annotation processor.
 */
@Component
@ConfigurationProperties(prefix = "auraboot.genai.pricing")
public class GenAiPricingProperties {

    /**
     * model-prefix → rate. Spring relaxed binding lowercases and dash-separates
     * keys, which matches how {@link GenAiPricing} normalises prefixes.
     */
    private Map<String, RateConfig> models = new LinkedHashMap<>();

    public Map<String, RateConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, RateConfig> models) {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }

    /** One configured rate card. Null cache rates mean "we have no cache rate". */
    public static class RateConfig {
        private BigDecimal input;
        private BigDecimal output;
        private BigDecimal cacheRead;
        private BigDecimal cacheWrite;

        public BigDecimal getInput() {
            return input;
        }

        public void setInput(BigDecimal input) {
            this.input = input;
        }

        public BigDecimal getOutput() {
            return output;
        }

        public void setOutput(BigDecimal output) {
            this.output = output;
        }

        public BigDecimal getCacheRead() {
            return cacheRead;
        }

        public void setCacheRead(BigDecimal cacheRead) {
            this.cacheRead = cacheRead;
        }

        public BigDecimal getCacheWrite() {
            return cacheWrite;
        }

        public void setCacheWrite(BigDecimal cacheWrite) {
            this.cacheWrite = cacheWrite;
        }
    }

    /**
     * Convert to the shape {@link GenAiPricing#quote} consumes. Entries missing
     * either mandatory rate are dropped rather than defaulted to zero: a
     * half-configured rate that silently prices output at $0 is the same class of
     * bug this class exists to remove.
     */
    public Map<String, GenAiPricing.Rate> toRates() {
        Map<String, GenAiPricing.Rate> out = new LinkedHashMap<>();
        models.forEach((prefix, cfg) -> {
            if (prefix == null || prefix.isBlank() || cfg == null) {
                return;
            }
            if (cfg.getInput() == null || cfg.getOutput() == null) {
                return;
            }
            out.put(prefix, new GenAiPricing.Rate(
                    cfg.getInput(), cfg.getOutput(), cfg.getCacheRead(), cfg.getCacheWrite()));
        });
        return out;
    }
}
