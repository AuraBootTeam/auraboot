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
 * Deterministic LLM token → cost computation for the durable usage ledger
 * (A-G6, P1; SoT §2.5 — {@code GenAiUsageRecord} is the billing source of truth,
 * <em>not</em> sampled OTel spans).
 *
 * <p>Prices are per 1M tokens in USD, keyed by a model prefix. Matching is
 * <strong>longest prefix first</strong>: {@code gpt-4o-mini} must never be priced
 * by the {@code gpt-4o} row. The previous implementation iterated a {@code Map.of}
 * and returned the first {@code startsWith} hit — and because the JDK randomises
 * immutable-map iteration order per JVM run (an internal SALT seeded at class
 * init), {@code gpt-4o-mini} was priced at {@code gpt-4o} rates in roughly a
 * quarter of JVM starts. That is a 16.7x overcharge on both input and output and
 * it changed on restart, which is the exact opposite of what this class promises.
 *
 * <p>A model with no matching prefix is <strong>unpriced</strong>, which is a
 * different fact from "costs zero" and is reported as such via
 * {@link Quote#priced()}. Callers must not collapse the two: a ledger row saying
 * {@code amount = 0, pricingVersion = <this table>} asserts "this table priced it
 * at zero", and writing that for a model the table has never heard of destroys the
 * one column that makes historical rows re-pricable.
 *
 * <p>Deliberately <em>not</em> shipping guessed prices for models we have no rate
 * for (qwen / qianwen, glm / zhipu, moonshot, newer deepseek variants…). A
 * confidently wrong price is worse than a visibly absent one, and per-deployment
 * contract rates differ from public list prices anyway. Extend at runtime via
 * {@code auraboot.genai.pricing.*} ({@link GenAiPricingProperties}) instead of
 * editing this table.
 *
 * <p>Pure helper (no Spring/IO) so the pricing math is trivially unit-tested.
 */
public final class GenAiPricing {

    /** Bump when the built-in table changes so historical rows stay auditable. */
    public static final String PRICING_VERSION = "2026-07-25";

    /**
     * Written to {@code pricing_version} when no rate matched, so an unpriced row
     * is distinguishable from a genuinely free one in SQL:
     * {@code WHERE pricing_version = 'unpriced'}.
     */
    public static final String UNPRICED_VERSION = "unpriced";

    /**
     * Written to {@code pricing_version} when the amount came from the vendor's own
     * reported cost rather than this table, so "we priced it" and "they told us" stay
     * distinguishable in the ledger.
     */
    public static final String PROVIDER_REPORTED_VERSION = "provider-reported";

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /** model-prefix → rate. Lowercased prefix match, longest prefix wins. */
    private static final Map<String, Rate> PRICES = buildDefaults();

    /** Built-in prefixes ordered longest-first: the deterministic match order. */
    private static final List<Map.Entry<String, Rate>> ORDERED = orderLongestFirst(PRICES);

    private GenAiPricing() {
    }

    /**
     * A rate card for one model prefix, USD per 1M tokens.
     *
     * <p>{@code cacheRead}/{@code cacheWrite} are nullable because vendors' cache
     * multipliers are not something this table should guess. When they are null and
     * the call reports cache tokens, the quote says so via
     * {@link Quote#cacheTokensUnpriced()} rather than silently dropping them —
     * cache-heavy agent traffic would otherwise understate the bill invisibly.
     */
    public record Rate(BigDecimal input, BigDecimal output,
                       BigDecimal cacheRead, BigDecimal cacheWrite) {

        public static Rate of(String input, String output) {
            return new Rate(new BigDecimal(input), new BigDecimal(output), null, null);
        }
    }

    /**
     * The outcome of pricing one call.
     *
     * @param amount              USD cost; {@link BigDecimal#ZERO} when unpriced
     * @param priced              whether a rate actually matched
     * @param pricingVersion      {@link #PRICING_VERSION} when priced, else {@link #UNPRICED_VERSION}
     * @param matchedPrefix       the prefix that matched, or {@code null}
     * @param cacheTokensUnpriced true when the call reported cache tokens but the
     *                            matched rate carries no cache rate, so
     *                            {@code amount} understates the real bill
     */
    public record Quote(BigDecimal amount, boolean priced, String pricingVersion,
                        String matchedPrefix, boolean cacheTokensUnpriced) {
    }

    /** Price one call against the built-in table only. */
    public static Quote quote(String model, Integer inputTokens, Integer outputTokens,
                              Integer cacheReadTokens, Integer cacheWriteTokens) {
        return quote(model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, Map.of());
    }

    /**
     * Price one call, letting {@code overrides} extend or replace the built-in
     * table. An override for the same prefix wins; longest prefix wins across
     * both tables, so a deployment can price {@code qwen-plus} — or correct
     * {@code gpt-4o-mini} to its contracted rate — without touching this file.
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
     * Compute USD cost for a call against the built-in table. Returns
     * {@link BigDecimal#ZERO} for unknown models or null/negative token counts
     * (never null).
     *
     * <p>Retained for callers that only need the number. A zero returned here is
     * ambiguous by construction — use {@link #quote} when the caller records
     * <em>why</em> the cost is what it is.
     */
    public static BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        Quote q = quote(model, inputTokens, outputTokens, null, null);
        return q.priced() ? q.amount() : BigDecimal.ZERO;
    }

    /** The built-in prefixes, longest first. Visible for tests / diagnostics. */
    public static List<String> knownPrefixes() {
        return ORDERED.stream().map(Map.Entry::getKey).toList();
    }

    private static Map.Entry<String, Rate> match(String model, Map<String, Rate> overrides) {
        if (model == null) {
            return null;
        }
        String m = model.toLowerCase(Locale.ROOT);

        // Merge then order when overrides exist: override maps are deployment-sized,
        // and merging here keeps longest-prefix-wins true ACROSS both tables rather
        // than only within each one.
        List<Map.Entry<String, Rate>> candidates = (overrides == null || overrides.isEmpty())
                ? ORDERED
                : orderLongestFirst(merged(overrides));

        for (Map.Entry<String, Rate> e : candidates) {
            if (m.startsWith(e.getKey())) {
                return e;
            }
        }
        return null;
    }

    private static Map<String, Rate> merged(Map<String, Rate> overrides) {
        Map<String, Rate> all = new LinkedHashMap<>(PRICES);
        overrides.forEach((k, v) -> {
            if (k != null && v != null) {
                all.put(k.toLowerCase(Locale.ROOT), v);
            }
        });
        return all;
    }

    /**
     * Longest prefix first, then alphabetically so the order is total and stable —
     * two prefixes of equal length must not resolve by hash order, which is the
     * defect this ordering exists to prevent.
     */
    private static List<Map.Entry<String, Rate>> orderLongestFirst(Map<String, Rate> prices) {
        List<Map.Entry<String, Rate>> list = new ArrayList<>(prices.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<String, Rate> e) -> e.getKey().length())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        return List.copyOf(list);
    }

    private static Map<String, Rate> buildDefaults() {
        Map<String, Rate> p = new LinkedHashMap<>();
        p.put("deepseek-chat", Rate.of("0.27", "1.10"));
        p.put("deepseek-reasoner", Rate.of("0.55", "2.19"));
        p.put("claude-3-5-haiku", Rate.of("0.80", "4.00"));
        p.put("claude-sonnet", Rate.of("3.00", "15.00"));
        p.put("claude-opus", Rate.of("15.00", "75.00"));
        p.put("gpt-4o", Rate.of("2.50", "10.00"));
        p.put("gpt-4o-mini", Rate.of("0.15", "0.60"));
        return Map.copyOf(p);
    }

    private static BigDecimal tokens(Integer t) {
        return (t == null || t < 0) ? BigDecimal.ZERO : new BigDecimal(t);
    }
}
