package com.auraboot.framework.agent.nlmodeling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Config-as-product provenance (FR-E4) for Prompt-to-App generated pages.
 *
 * <p>Every page block carries two provenance markers:
 * <ul>
 *   <li>{@code source} ∈ {@code {ai, manual, template}} — who authored the block
 *       (AI generation, a hand-edit in the designer, or a starter template).</li>
 *   <li>{@code locked} (boolean) — an explicit pin: a locked block is never touched
 *       by a re-generation, even if the new generation would drop or replace it.</li>
 * </ul>
 *
 * <p>{@link #mergeRegeneration(Map, Map)} is the pure merge applied when a user
 * re-generates a page they have already hand-tuned: it preserves hand-edits by
 * default (a {@code manual} block is not overwritten) and honours explicit locks (a
 * {@code locked} block survives even when the regeneration omits it), while letting
 * fresh AI generations overwrite their own previous, unlocked AI output. This keeps
 * the generated config a living product artifact rather than a throwaway draft.
 *
 * <p>Stateless / pure — no LLM, no DB; fully unit-testable.
 */
public final class PageConfigProvenance {

    /** Provenance marker key on a block: who authored it. */
    public static final String SOURCE = "source";
    /** Provenance marker key on a block: an explicit pin against regeneration. */
    public static final String LOCKED = "locked";

    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_TEMPLATE = "template";

    private static final Set<String> VALID_SOURCES = Set.of(SOURCE_AI, SOURCE_MANUAL, SOURCE_TEMPLATE);

    private PageConfigProvenance() {
    }

    /** Whether {@code source} is one of the three recognized provenance values. */
    public static boolean isValidSource(String source) {
        return source != null && VALID_SOURCES.contains(source);
    }

    /**
     * Tags every block of a freshly generated page with provenance defaults: an
     * untagged (or invalid-source) block is marked {@code source=ai}; a block that
     * already declares a valid {@code source} (e.g. a {@code template} block lifted
     * from a starter, or a {@code manual} block carried over) keeps it. {@code locked}
     * defaults to {@code false} when absent. Mutates the page in place.
     */
    @SuppressWarnings("unchecked")
    public static void tagGenerated(Map<String, Object> page) {
        if (page == null || !(page.get("blocks") instanceof List<?> blocks)) {
            return;
        }
        for (Object blockObj : blocks) {
            if (blockObj instanceof Map<?, ?> blockRaw) {
                Map<String, Object> block = (Map<String, Object>) blockRaw;
                Object source = block.get(SOURCE);
                if (!(source instanceof String s) || !isValidSource(s)) {
                    block.put(SOURCE, SOURCE_AI);
                }
                if (!(block.get(LOCKED) instanceof Boolean)) {
                    block.put(LOCKED, Boolean.FALSE);
                }
            }
        }
    }

    /**
     * Merges a re-generated page config over an existing (possibly hand-tuned) one,
     * preserving hand-edits:
     * <ul>
     *   <li>A block in {@code existing} that is {@code locked} OR {@code source=manual}
     *       is <b>protected</b>: it is kept verbatim and a regenerated block with the
     *       same {@code id} does NOT overwrite it.</li>
     *   <li>Any other existing block (unlocked AI/template output) is replaced by the
     *       regenerated block of the same id, or dropped if the regeneration omits it.</li>
     *   <li>Regenerated blocks whose id is not a protected existing block are taken
     *       as-is (new or refreshed AI output).</li>
     *   <li>Protected existing blocks that the regeneration dropped are re-appended so
     *       a {@code locked} block survives even when regen omits it.</li>
     * </ul>
     * Result block order: regenerated order first, then surviving protected blocks that
     * the regeneration did not carry. The returned map is the regenerated page with its
     * {@code blocks} replaced (the regenerated page's other top-level fields win). When
     * {@code existing} is {@code null}/blockless the regenerated config is returned as-is.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeRegeneration(Map<String, Object> existing,
                                                        Map<String, Object> regenerated) {
        if (existing == null || regenerated == null) {
            return regenerated;
        }
        List<Map<String, Object>> existingBlocks = blocksOf(existing);
        List<Map<String, Object>> regeneratedBlocks = blocksOf(regenerated);
        if (existingBlocks.isEmpty()) {
            return regenerated;
        }

        // Index protected existing blocks by id (locked OR hand-edited manual).
        Map<String, Map<String, Object>> protectedById = new LinkedHashMap<>();
        for (Map<String, Object> block : existingBlocks) {
            if (isProtected(block) && block.get("id") instanceof String id && !id.isBlank()) {
                protectedById.put(id, block);
            }
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> consumedProtected = new LinkedHashSet<>();
        for (Map<String, Object> regen : regeneratedBlocks) {
            String id = regen.get("id") instanceof String s ? s : null;
            if (id != null && protectedById.containsKey(id)) {
                // The user pinned / hand-edited this block — keep theirs, drop the regen.
                merged.add(protectedById.get(id));
                consumedProtected.add(id);
            } else {
                merged.add(regen);
            }
        }
        // Re-append protected blocks the regeneration dropped (e.g. a locked hero the
        // new generation no longer emits) so the pin truly survives.
        for (Map.Entry<String, Map<String, Object>> e : protectedById.entrySet()) {
            if (!consumedProtected.contains(e.getKey())) {
                merged.add(e.getValue());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>(regenerated);
        result.put("blocks", new ArrayList<Object>(merged));
        return result;
    }

    private static boolean isProtected(Map<String, Object> block) {
        return Boolean.TRUE.equals(block.get(LOCKED))
                || SOURCE_MANUAL.equals(block.get(SOURCE));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocksOf(Map<String, Object> page) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (page != null && page.get("blocks") instanceof List<?> blocks) {
            for (Object blockObj : blocks) {
                if (blockObj instanceof Map<?, ?> blockRaw) {
                    out.add((Map<String, Object>) blockRaw);
                }
            }
        }
        return out;
    }
}
