package com.auraboot.framework.agent.memory;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure Java (stateless, no DB, no Spring deps beyond stereotype) scorer for
 * the L1 -> L2 promotion decision.
 *
 * <p>Design: see
 * {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4.2}.
 *
 * <p>Formula (weighted sum, not product — the brief's shorthand
 * "importance × access_count × recency × unique_cosine" describes the four
 * factors, implemented per the design-doc formula below):
 *
 * <pre>
 *   score(m) = w_imp  * norm(importance, 0..10)
 *            + w_acc  * norm(log1p(access_count), 0..log1p(20))
 *            + w_rec  * exp(-age_hours / 72)           // 72h half-life
 *            + w_uni  * (1 - maxCosineToL2)            // uniqueness
 *
 *   default weights: w_imp=0.35  w_acc=0.25  w_rec=0.15  w_uni=0.25
 *   default threshold: 0.65
 * </pre>
 *
 * <p>This class is deliberately:
 * <ul>
 *   <li>Stateless — no mutable fields; safe for parallel use.</li>
 *   <li>Free of fallback / retry / ensure — malformed input throws.</li>
 *   <li>Free of DB/LLM/network — callers pass pre-computed
 *       {@code maxCosineToL2} (the max cosine similarity of this L1 row's
 *       embedding against existing L2 rows in the same
 *       (tenant, scope, scope_key) partition).</li>
 * </ul>
 */
@Service
public class MemoryTierEvaluator {

    public static final double DEFAULT_W_IMPORTANCE = 0.35;
    public static final double DEFAULT_W_ACCESS = 0.25;
    public static final double DEFAULT_W_RECENCY = 0.15;
    public static final double DEFAULT_W_UNIQUENESS = 0.25;
    public static final double DEFAULT_THRESHOLD = 0.65;

    /** Cap used when normalising {@code log1p(access_count)}. */
    public static final int ACCESS_COUNT_CAP = 20;
    /** Recency half-life (72 hours per design §4.2). */
    public static final double RECENCY_HALF_LIFE_HOURS = 72.0;

    /** Maximum raw importance per existing ab_agent_memory column semantics (0..10). */
    public static final int IMPORTANCE_MAX = 10;

    /** Default weight set, {@code weights_version = "v1"}. */
    public static final Weights DEFAULT_WEIGHTS = new Weights(
            DEFAULT_W_IMPORTANCE,
            DEFAULT_W_ACCESS,
            DEFAULT_W_RECENCY,
            DEFAULT_W_UNIQUENESS,
            "v1");

    /**
     * Compute score for an L1 candidate using the default weight set.
     */
    public ScoreResult score(Candidate candidate, Instant now) {
        return score(candidate, now, DEFAULT_WEIGHTS);
    }

    /**
     * Compute score for an L1 candidate.
     *
     * <p>Raises {@link IllegalArgumentException} on malformed input — no fallback.
     */
    public ScoreResult score(Candidate candidate, Instant now, Weights weights) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(weights, "weights");

        validate(candidate);

        double impFactor = normImportance(candidate.importance());
        double accFactor = normAccess(candidate.accessCount());
        double recFactor = recency(candidate.createdAt(), now);
        double uniFactor = uniqueness(candidate.maxCosineToL2());

        double score = weights.importance() * impFactor
                + weights.access() * accFactor
                + weights.recency() * recFactor
                + weights.uniqueness() * uniFactor;

        return new ScoreResult(
                round4(score),
                round4(impFactor),
                round4(accFactor),
                round4(recFactor),
                round4(uniFactor),
                weights.version(),
                now);
    }

    /** Convenience: apply the default threshold to the given result. */
    public boolean shouldPromote(ScoreResult result) {
        return shouldPromote(result, DEFAULT_THRESHOLD);
    }

    /** Convenience: apply a caller-supplied threshold. */
    public boolean shouldPromote(ScoreResult result, double threshold) {
        Objects.requireNonNull(result, "result");
        return result.score() >= threshold;
    }

    // --- factor functions (package-private for unit testing if ever needed) ---

    static double normImportance(int importance) {
        return clamp01((double) importance / IMPORTANCE_MAX);
    }

    static double normAccess(int accessCount) {
        double numer = Math.log1p(accessCount);
        double denom = Math.log1p(ACCESS_COUNT_CAP);
        return clamp01(numer / denom);
    }

    static double recency(Instant createdAt, Instant now) {
        double ageHours = Duration.between(createdAt, now).toMillis() / 3_600_000.0;
        // Negative age (created in the future) is pinned to 0h -> recency 1.
        if (ageHours < 0.0) {
            ageHours = 0.0;
        }
        return Math.exp(-ageHours / RECENCY_HALF_LIFE_HOURS);
    }

    /**
     * {@code uniqueness = 1 - maxCosineToL2}. Input cosine is clamped to
     * [0,1] — the scoring partition only inspects same-scope L2 rows and we
     * treat any negative cosine as "no similarity" (unusual with normalised
     * embeddings but tolerated).
     */
    static double uniqueness(double maxCosineToL2) {
        double c = clamp01(maxCosineToL2);
        return 1.0 - c;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static void validate(Candidate c) {
        if (c.importance() < 0 || c.importance() > IMPORTANCE_MAX) {
            throw new IllegalArgumentException(
                    "importance out of range [0," + IMPORTANCE_MAX + "]: " + c.importance());
        }
        if (c.accessCount() < 0) {
            throw new IllegalArgumentException("accessCount must be >= 0: " + c.accessCount());
        }
        if (c.createdAt() == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        // Cosine domain is [-1, 1]; we only warn-via-clamp on negatives but
        // reject clearly-out-of-domain values as a data contract bug.
        if (c.maxCosineToL2() > 1.0 || c.maxCosineToL2() < -1.0) {
            throw new IllegalArgumentException(
                    "maxCosineToL2 out of [-1,1]: " + c.maxCosineToL2());
        }
    }

    // ---------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------

    /**
     * Scorer input. All values pre-fetched by the caller; the evaluator does
     * no IO.
     *
     * @param importance      raw {@code ab_agent_memory.importance} in [0, 10]
     * @param accessCount     raw {@code ab_agent_memory.access_count}; clamped
     *                        via log1p to soften runaway values
     * @param createdAt       {@code ab_agent_memory.created_at}
     * @param maxCosineToL2   max cosine similarity of this L1 row's embedding
     *                        vs existing L2 rows in the same (tenant, scope,
     *                        scope_key); pass {@code 0.0} when there are no
     *                        L2 rows or when embedding is null (see design §10)
     */
    public record Candidate(
            int importance,
            int accessCount,
            Instant createdAt,
            double maxCosineToL2) {
    }

    /**
     * Weight tuple — backed by configuration keys
     * {@code acp.memory.l1l2.weights.*}. Defaults in {@link #DEFAULT_WEIGHTS}.
     */
    public record Weights(
            double importance,
            double access,
            double recency,
            double uniqueness,
            String version) {

        public Weights {
            if (importance < 0 || access < 0 || recency < 0 || uniqueness < 0) {
                throw new IllegalArgumentException("weights must be non-negative");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("weights version must not be blank");
            }
        }
    }

    /**
     * Scorer output — carries the composite score plus each factor for audit.
     * Maps 1:1 to the JSONB {@code score_snapshot} structure stored on the
     * promoted L2 row (see design §5).
     */
    public record ScoreResult(
            double score,
            double importanceFactor,
            double accessFactor,
            double recencyFactor,
            double uniquenessFactor,
            String weightsVersion,
            Instant computedAt) {
    }
}
