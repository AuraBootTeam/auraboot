package com.auraboot.framework.agent.util;

/**
 * Pure-Java cosine similarity for embedding vectors (PR-65).
 *
 * <p>Used when pgvector is unavailable or when caller has vectors already
 * materialised in memory. Returns {@code 0.0} for null / empty / length
 * mismatch — caller can treat missing embeddings as "no signal" rather
 * than "dissimilar" without an extra null-check, at the cost of folding
 * them into the same bucket. That trade-off is explicit in the design
 * (§5 "embedding endpoint unreachable → store null, skip during scan").
 */
public final class EmbeddingSimilarity {

    private EmbeddingSimilarity() {}

    /**
     * Standard cosine similarity in {@code [-1.0, 1.0]} for valid input, or
     * {@code 0.0} for missing/mismatched inputs.
     */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Overload for {@code float[]} vectors (pgvector native type). */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
