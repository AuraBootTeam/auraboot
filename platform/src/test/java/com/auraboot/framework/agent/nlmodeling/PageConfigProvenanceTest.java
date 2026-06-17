package com.auraboot.framework.agent.nlmodeling;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for config-as-product provenance (FR-E4): block-level
 * {@code source}/{@code locked} tagging on a generated page config, and the
 * {@link PageConfigProvenance#mergeRegeneration} merge that preserves hand-edits
 * across a re-generation. No LLM, no DB.
 */
class PageConfigProvenanceTest {

    private static Map<String, Object> block(String id, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> page(String pageKey, List<Map<String, Object>> blocks) {
        Map<String, Object> p = new HashMap<>();
        p.put("pageKey", pageKey);
        p.put("blocks", new ArrayList<>(blocks));
        return p;
    }

    // =====================================================================
    // Provenance tagging
    // =====================================================================

    @Test
    void tagGenerated_marksUntaggedBlocksAsAiAndUnlocked() {
        Map<String, Object> p = page("book_list", List.of(block("toolbar"), block("table")));
        PageConfigProvenance.tagGenerated(p);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) p.get("blocks");
        for (Map<String, Object> b : blocks) {
            assertEquals("ai", b.get("source"), "generated blocks default to source=ai");
            assertEquals(Boolean.FALSE, b.get("locked"), "generated blocks default to unlocked");
        }
    }

    @Test
    void tagGenerated_preservesExplicitManualOrTemplateSourceAndLock() {
        Map<String, Object> p = page("book_list", List.of(
                block("kept", "source", "manual", "locked", true),
                block("tpl", "source", "template"),
                block("fresh")));
        PageConfigProvenance.tagGenerated(p);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) p.get("blocks");
        assertEquals("manual", blocks.get(0).get("source"), "an explicit manual source is preserved");
        assertEquals(Boolean.TRUE, blocks.get(0).get("locked"), "an explicit lock is preserved");
        assertEquals("template", blocks.get(1).get("source"));
        assertEquals(Boolean.FALSE, blocks.get(1).get("locked"), "missing lock defaults to false");
        assertEquals("ai", blocks.get(2).get("source"));
    }

    @Test
    void isValidSource_acceptsOnlyTheThreeProvenanceValues() {
        assertTrue(PageConfigProvenance.isValidSource("ai"));
        assertTrue(PageConfigProvenance.isValidSource("manual"));
        assertTrue(PageConfigProvenance.isValidSource("template"));
        assertFalse(PageConfigProvenance.isValidSource("robot"));
        assertFalse(PageConfigProvenance.isValidSource(null));
    }

    // =====================================================================
    // mergeRegeneration — the hand-edit-preserving merge
    // =====================================================================

    @Test
    void mergeRegeneration_lockedBlockSurvivesRegen() {
        Map<String, Object> existing = page("book_list", List.of(
                block("hero", "source", "ai", "locked", true, "title", "Hand-tuned hero"),
                block("table", "source", "ai", "locked", false, "columns", "old")));
        // regenerated drops the hero entirely and changes the table
        Map<String, Object> regenerated = page("book_list", List.of(
                block("table", "source", "ai", "locked", false, "columns", "new"),
                block("footer", "source", "ai", "locked", false)));

        Map<String, Object> merged = PageConfigProvenance.mergeRegeneration(existing, regenerated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) merged.get("blocks");
        Map<String, Object> hero = byId(blocks, "hero");
        assertNotNull(hero, "a locked block must survive regeneration even if regen omits it");
        assertEquals("Hand-tuned hero", hero.get("title"), "the locked block keeps its hand-tuned content");
        // unlocked ai block is replaced by the regenerated version
        Map<String, Object> table = byId(blocks, "table");
        assertEquals("new", table.get("columns"), "an unlocked ai block is overwritten by regen");
        // a brand-new regenerated block is added
        assertNotNull(byId(blocks, "footer"), "new regenerated blocks are added");
    }

    @Test
    void mergeRegeneration_manualBlockIsNotOverwritten() {
        Map<String, Object> existing = page("book_list", List.of(
                block("notes", "source", "manual", "locked", false, "text", "my note")));
        Map<String, Object> regenerated = page("book_list", List.of(
                block("notes", "source", "ai", "locked", false, "text", "regenerated note")));

        Map<String, Object> merged = PageConfigProvenance.mergeRegeneration(existing, regenerated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) merged.get("blocks");
        Map<String, Object> notes = byId(blocks, "notes");
        assertEquals("my note", notes.get("text"),
                "a hand-edited (manual) block must not be overwritten by regen (default preserve)");
        assertEquals("manual", notes.get("source"), "the manual provenance is preserved");
    }

    @Test
    void mergeRegeneration_unlockedAiBlockTakesRegeneratedContent() {
        Map<String, Object> existing = page("book_list", List.of(
                block("table", "source", "ai", "locked", false, "columns", "old")));
        Map<String, Object> regenerated = page("book_list", List.of(
                block("table", "source", "ai", "locked", false, "columns", "new")));

        Map<String, Object> merged = PageConfigProvenance.mergeRegeneration(existing, regenerated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) merged.get("blocks");
        assertEquals(1, blocks.size());
        assertEquals("new", byId(blocks, "table").get("columns"),
                "an unlocked ai block is fully replaced by the regenerated content");
    }

    @Test
    void mergeRegeneration_nullExistingReturnsRegenerated() {
        Map<String, Object> regenerated = page("x", List.of(block("a", "source", "ai")));
        assertSame(regenerated, PageConfigProvenance.mergeRegeneration(null, regenerated),
                "with no existing config, the regenerated config is returned as-is");
    }

    @Test
    void mergeRegeneration_preservesOrderingRegeneratedThenSurvivingProtected() {
        Map<String, Object> existing = page("x", List.of(
                block("a", "source", "ai", "locked", false),
                block("locked", "source", "ai", "locked", true)));
        Map<String, Object> regenerated = page("x", List.of(
                block("a", "source", "ai", "locked", false),
                block("b", "source", "ai", "locked", false)));

        Map<String, Object> merged = PageConfigProvenance.mergeRegeneration(existing, regenerated);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) (List<?>) merged.get("blocks");
        // regenerated blocks come first (a, b), then surviving protected blocks (locked)
        assertEquals(List.of("a", "b", "locked"),
                blocks.stream().map(bk -> bk.get("id")).toList(),
                "regenerated order is kept; surviving protected blocks are appended");
    }

    private static Map<String, Object> byId(List<Map<String, Object>> blocks, String id) {
        return blocks.stream().filter(b -> id.equals(b.get("id"))).findFirst().orElse(null);
    }
}
