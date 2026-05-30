package com.auraboot.framework.connector.normalization;

import java.util.Map;
import java.util.stream.Stream;

/**
 * SPI for the <em>L3 data-cleansing layer</em> of the IDA data platform
 * (PRD 16 §semantic.yml, strategy patch §15-数据平台战略决策补丁.md §三 hybrid 图).
 *
 * <p>Implementations apply a declarative set of field-level transformation rules
 * ({@link NormalizationConfig}) to a raw-record stream produced by a SaaS connector
 * {@code read()} call before the records are written to the downstream storage layer.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The returned stream is lazy: each record is normalised on demand during
 *       terminal operations. Implementations must not materialise the full source
 *       stream eagerly.</li>
 *   <li>Fields not mentioned in {@code cfg.fields()} are passed through unchanged
 *       (open-world assumption).</li>
 *   <li>A rule that cannot be applied (e.g. non-parseable value for a TIMESTAMP
 *       rule) should leave the field value unchanged and optionally emit a warning
 *       — it must not propagate an exception out of the stream pipeline.</li>
 *   <li>Neither {@code cfg} nor {@code source} may be {@code null}.</li>
 * </ul>
 *
 * <h2>Extension points</h2>
 * <p>The primary in-process implementation is {@link InMemoryNormalizationEngine}.
 *
 * <p><strong>Flink reservation:</strong> A {@code FlinkNormalizationEngine} binding
 * should be introduced when a customer scenario requires sub-second SLA with complex
 * windowing or stateful aggregation (see {@code 15-数据平台战略决策补丁.md} §六 for
 * the trigger criteria). That implementation would translate each {@link NormalizationConfig.FieldRule}
 * into a Flink {@code MapFunction} operator on a {@code DataStream<Map<String,Object>>}.
 * No Flink dependency is introduced in this PR; the interface is designed to remain
 * binary-compatible with both the in-process and the Flink execution model.
 *
 * @since 5.3.0
 */
public interface NormalizationEngine {

    /**
     * Apply the declarative normalization rules in {@code cfg} to each record in
     * {@code source}, returning a lazily transformed stream.
     *
     * @param cfg    non-null normalization configuration; {@code cfg.fields()} may
     *               be empty, in which case records are passed through unmodified
     * @param source non-null lazy stream of raw record maps from the SaaS connector
     * @return lazy stream of normalised record maps; guaranteed non-null
     */
    Stream<Map<String, Object>> apply(NormalizationConfig cfg, Stream<Map<String, Object>> source);
}
