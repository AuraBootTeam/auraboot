package com.auraboot.framework.observability;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic LLM token-to-cost computation for the durable usage ledger.
 *
 * <p>Prices are USD per one million tokens, keyed by model prefix. Matching is
 * longest-prefix-first across built-in and deployment-configured rates. Unknown
 * models are recorded as {@link #UNPRICED_VERSION}; they are not asserted to be
 * free and remain re-priceable from their token counts.
 */
public final class GenAiPricing {

    /** Bump when the built-in table changes so historical rows stay auditable. */
    public static final String PRICING_VERSION = "2026-07-27";

    /** Pricing provenance for a row whose model matched no configured rate. */
    public static final String UNPRICED_VERSION = "unpriced";

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final Map<String, Rate> PRICES = buildDefaults();
    private static final List<Map.Entry<String, Rate>> ORDERED = orderLongestFirst(PRICES);

    private GenAiPricing() {
    }

    /** One model-prefix rate card, in USD per one million tokens. */
    public record Rate(BigDecimal input, BigDecimal output,
                       BigDecimal cacheRead, BigDecimal cacheWrite) {

        public static Rate of(String input, String output) {
            return new Rate(new BigDecimal(input), new BigDecimal(output), null, null);
        }
    }

    /**
     * Result of pricing one call.
     *
     * @param amount USD cost; zero when no rate matched
     * @param priced whether a rate matched
     * @param pricingVersion built-in/configured table version, or {@code unpriced}
     * @param matchedPrefix matched model prefix, or {@code null}
     * @param cacheTokensUnpriced whether cache tokens were reported without cache rates
     */
    public record Quote(BigDecimal amount, boolean priced, String pricingVersion,
                        String matchedPrefix, boolean cacheTokensUnpriced) {
    }

    /** Price one call against the built-in table. */
    public static Quote quote(String model, Integer inputTokens, Integer outputTokens,
                              Integer cacheReadTokens, Integer cacheWriteTokens) {
        return quote(model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, Map.of());
    }

    /**
     * Price one call with deployment rates layered over built-ins. An override
     * replaces the same prefix; a more-specific prefix wins across both sources.
     */
    public static Quote quote(String model, Integer inputTokens, Integer outputTokens,
                              Integer cacheReadTokens, Integer cacheWriteTokens,
                              Map<String, Rate> overrides) {
        Map.Entry<String, Rate> match = match(model, overrides);
        if (match == null) {
            return new Quote(BigDecimal.ZERO, false, UNPRICED_VERSION, null, false);
        }

        Rate rate = match.getValue();
        BigDecimal total = tokens(inputTokens).multiply(rate.input())
                .add(tokens(outputTokens).multiply(rate.output()));

        boolean cacheUnpriced = false;
        BigDecimal cacheRead = tokens(cacheReadTokens);
        BigDecimal cacheWrite = tokens(cacheWriteTokens);
        if (rate.cacheRead() != null) {
            total = total.add(cacheRead.multiply(rate.cacheRead()));
        } else if (cacheRead.signum() > 0) {
            cacheUnpriced = true;
        }
        if (rate.cacheWrite() != null) {
            total = total.add(cacheWrite.multiply(rate.cacheWrite()));
        } else if (cacheWrite.signum() > 0) {
            cacheUnpriced = true;
        }

        BigDecimal amount = total.divide(PER_MILLION, new MathContext(10, RoundingMode.HALF_UP))
                .setScale(6, RoundingMode.HALF_UP);
        return new Quote(amount, true, PRICING_VERSION, match.getKey(), cacheUnpriced);
    }

    /**
     * Compatibility API for callers that only need a number. Prefer
     * {@link #quote} when persisting cost provenance.
     */
    public static BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        Quote quote = quote(model, inputTokens, outputTokens, null, null);
        return quote.priced() ? quote.amount() : BigDecimal.ZERO;
    }

    /** Built-in model prefixes in deterministic match order. */
    public static List<String> knownPrefixes() {
        return ORDERED.stream().map(Map.Entry::getKey).toList();
    }

    private static Map.Entry<String, Rate> match(String model, Map<String, Rate> overrides) {
        if (model == null) {
            return null;
        }
        String normalizedModel = model.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Rate>> candidates = overrides == null || overrides.isEmpty()
                ? ORDERED
                : orderLongestFirst(merged(overrides));
        for (Map.Entry<String, Rate> candidate : candidates) {
            if (normalizedModel.startsWith(candidate.getKey())) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, Rate> merged(Map<String, Rate> overrides) {
        Map<String, Rate> all = new LinkedHashMap<>(PRICES);
        overrides.forEach((prefix, rate) -> {
            if (prefix != null && !prefix.isBlank() && rate != null) {
                all.put(prefix.toLowerCase(Locale.ROOT), rate);
            }
        });
        return all;
    }

    private static List<Map.Entry<String, Rate>> orderLongestFirst(Map<String, Rate> prices) {
        List<Map.Entry<String, Rate>> ordered = new ArrayList<>(prices.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, Rate> entry) -> entry.getKey().length())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        return List.copyOf(ordered);
    }

    private static Map<String, Rate> buildDefaults() {
        Map<String, Rate> rates = new LinkedHashMap<>();
        rates.put("deepseek-chat", Rate.of("0.27", "1.10"));
        rates.put("deepseek-reasoner", Rate.of("0.55", "2.19"));
        rates.put("claude-3-5-haiku", Rate.of("0.80", "4.00"));
        rates.put("claude-sonnet", Rate.of("3.00", "15.00"));
        rates.put("claude-opus", Rate.of("15.00", "75.00"));
        rates.put("gpt-4o", Rate.of("2.50", "10.00"));
        return Map.copyOf(rates);
    }

    private static BigDecimal tokens(Integer count) {
        return count == null || count < 0 ? BigDecimal.ZERO : new BigDecimal(count);
    }
}
