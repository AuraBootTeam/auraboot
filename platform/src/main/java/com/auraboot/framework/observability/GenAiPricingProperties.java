package com.auraboot.framework.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment-specific LLM rates layered over {@link GenAiPricing}'s built-ins.
 *
 * <p>Rates are exact decimal USD values per one million tokens. Prefixes match
 * model ids case-insensitively, with the longest prefix winning.
 *
 * <pre>{@code
 * auraboot:
 *   genai:
 *     pricing:
 *       models:
 *         qwen-plus:
 *           input: "0.40"
 *           output: "1.20"
 *           cache-read: "0.08"
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "auraboot.genai.pricing")
public class GenAiPricingProperties {

    private Map<String, RateConfig> models = new LinkedHashMap<>();

    public Map<String, RateConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, RateConfig> models) {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }

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
     * Drop partial configurations rather than silently pricing a token class at
     * zero. Cache rates remain optional and are surfaced as incomplete by quotes.
     */
    public Map<String, GenAiPricing.Rate> toRates() {
        Map<String, GenAiPricing.Rate> rates = new LinkedHashMap<>();
        models.forEach((prefix, config) -> {
            if (prefix == null || prefix.isBlank() || config == null
                    || config.getInput() == null || config.getOutput() == null) {
                return;
            }
            rates.put(prefix, new GenAiPricing.Rate(
                    config.getInput(), config.getOutput(),
                    config.getCacheRead(), config.getCacheWrite()));
        });
        return rates;
    }
}
