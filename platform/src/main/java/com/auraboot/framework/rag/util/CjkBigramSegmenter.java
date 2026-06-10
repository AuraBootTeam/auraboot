package com.auraboot.framework.rag.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Dictionary-free CJK bigram segmentation for PostgreSQL full-text search (G2).
 *
 * <p>The platform indexes chunk text with {@code to_tsvector('simple', ...)} which has
 * no CJK tokenizer — an unsegmented Chinese sentence becomes one giant token and the
 * BM25 leg of hybrid retrieval never matches. This segmenter expands every CJK run
 * into overlapping character bigrams (the Lucene/Elasticsearch {@code cjk} analyzer
 * approach), applied symmetrically at index time ({@link #segment}) and query time
 * ({@link #tsQueryTerms}), so {@code tsvector} and {@code tsquery} tokens line up.
 *
 * <p>Chosen over the {@code zhparser} PG extension to avoid an extension dependency in
 * every runtime environment, and over a dictionary segmenter for determinism — see
 * docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md §5 D1.
 */
public final class CjkBigramSegmenter {

    private CjkBigramSegmenter() {
    }

    /**
     * Segment text for {@code to_tsvector('simple', ...)}: CJK runs become
     * space-separated overlapping bigrams; non-CJK text passes through with
     * whitespace collapsed.
     */
    public static String segment(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() * 2);
        StringBuilder cjkRun = new StringBuilder();
        boolean lastWasSpace = true;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjkRun.appendCodePoint(cp);
            } else {
                lastWasSpace = flushCjkRun(out, cjkRun, lastWasSpace);
                if (Character.isWhitespace(cp)) {
                    if (!lastWasSpace) {
                        out.append(' ');
                        lastWasSpace = true;
                    }
                } else {
                    out.appendCodePoint(cp);
                    lastWasSpace = false;
                }
            }
            i += Character.charCount(cp);
        }
        flushCjkRun(out, cjkRun, lastWasSpace);
        return out.toString().strip();
    }

    /**
     * Tokenize a user query into tsquery terms: CJK runs become overlapping bigrams,
     * latin/digit runs become words, punctuation is dropped.
     */
    public static List<String> tsQueryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder wordRun = new StringBuilder();

        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            if (isCjk(cp)) {
                flushWord(terms, wordRun);
                cjkRun.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp) || cp == '_') {
                flushBigrams(terms, cjkRun);
                wordRun.appendCodePoint(cp);
            } else {
                flushWord(terms, wordRun);
                flushBigrams(terms, cjkRun);
            }
            i += Character.charCount(cp);
        }
        flushWord(terms, wordRun);
        flushBigrams(terms, cjkRun);
        return terms;
    }

    /** Append a CJK run's bigrams (space separated) to {@code out}; returns new lastWasSpace. */
    private static boolean flushCjkRun(StringBuilder out, StringBuilder cjkRun, boolean lastWasSpace) {
        if (cjkRun.length() == 0) {
            return lastWasSpace;
        }
        if (!lastWasSpace) {
            out.append(' ');
        }
        List<String> bigrams = new ArrayList<>();
        flushBigrams(bigrams, cjkRun);
        out.append(String.join(" ", bigrams));
        out.append(' ');
        return true;
    }

    /** Expand a CJK run into overlapping bigrams (unigram when run length is 1) and clear it. */
    private static void flushBigrams(List<String> terms, StringBuilder cjkRun) {
        int n = cjkRun.length();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            terms.add(cjkRun.toString());
        } else {
            for (int i = 0; i < n - 1; i++) {
                terms.add(cjkRun.substring(i, i + 2));
            }
        }
        cjkRun.setLength(0);
    }

    private static void flushWord(List<String> terms, StringBuilder wordRun) {
        if (wordRun.length() > 0) {
            terms.add(wordRun.toString());
            wordRun.setLength(0);
        }
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
