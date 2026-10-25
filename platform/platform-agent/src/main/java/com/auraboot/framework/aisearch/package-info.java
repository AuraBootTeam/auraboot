/**
 * Natural-language structured search (NL → filter) over dynamic models.
 *
 * <p>This package was previously {@code framework.ai}, a name that read like the
 * AI/LLM base layer. It is not: the LLM provider abstractions, agent runtime,
 * trace, memory and eval all live in {@code framework.agent}. This package is a
 * single feature — turning a user's natural-language query into a structured
 * search filter — and nothing else depends on it (ARCH-010).
 */
package com.auraboot.framework.aisearch;
