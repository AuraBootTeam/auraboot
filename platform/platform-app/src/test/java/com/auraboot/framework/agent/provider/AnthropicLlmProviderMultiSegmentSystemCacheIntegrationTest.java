package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AnthropicLlmProvider}'s multi-segment system
 * prompt cache support (ACP B.3 advanced).
 *
 * <p>The unified {@link LlmChatRequest} carries an optional
 * {@code systemSegments} list — each segment has text + a {@code cacheable}
 * flag. The Anthropic provider emits a content-block array where:
 * <ul>
 *   <li>{@code cacheable=true} segments above the 1024-token floor get a
 *       {@code cache_control: {"type":"ephemeral"}} marker.</li>
 *   <li>{@code cacheable=true} segments BELOW the floor still ship as plain
 *       text (no marker) so we don't waste an Anthropic cache slot on a
 *       prefix that would never actually cache.</li>
 *   <li>{@code cacheable=false} segments never get a marker — they sit
 *       inline so a session-level suffix change does not bust the prefix
 *       cache entry.</li>
 * </ul>
 *
 * <p>Back-compat: when {@code systemSegments} is null/empty the provider
 * falls back to wrapping {@code systemPrompt} as a single ephemeral block —
 * the legacy single-string path stays byte-identical.
 *
 * <p>This test directly invokes the package-private
 * {@code buildAnthropicRequest} converter so we can introspect the wire
 * shape without spinning up a WebClient. Real PostgreSQL + Redis are still
 * required (BaseIntegrationTest) per project red-line.
 */
class AnthropicLlmProviderMultiSegmentSystemCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AnthropicLlmProvider provider;

    /**
     * Build a system segment — convenience constructor since the test cases
     * below need many of them and the Lombok builder is verbose for two
     * fields.
     */
    private LlmChatRequest.SystemSegment seg(String text, boolean cacheable) {
        return LlmChatRequest.SystemSegment.builder().text(text).cacheable(cacheable).build();
    }

    /**
     * Pad text to at least {@link AnthropicLlmProvider#CACHE_MIN_TOKENS} *
     * 4 chars (≈ 1024 tokens at the provider's preflight heuristic) so the
     * 1024-token floor lets the cache_control marker through.
     */
    private String aboveCacheFloor(String marker) {
        int target = AnthropicLlmProvider.CACHE_MIN_TOKENS * 4 + 16;
        StringBuilder sb = new StringBuilder(target);
        sb.append(marker).append(' ');
        while (sb.length() < target) {
            sb.append("filler ");
        }
        return sb.toString();
    }

    /**
     * Invoke the provider's package-private converter end-to-end so the
     * system-segment branch of {@link AnthropicLlmProvider#buildAnthropicRequest}
     * is exercised against the wired Spring bean (no hand-built provider).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> systemBlocksOf(LlmChatRequest req) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
                "buildAnthropicRequest", LlmChatRequest.class);
        m.setAccessible(true);
        Object anthropicReq = m.invoke(provider, req);
        Method getSystem = anthropicReq.getClass().getDeclaredMethod("getSystem");
        getSystem.setAccessible(true);
        Object system = getSystem.invoke(anthropicReq);
        // null-system means the provider returned no blocks — let the caller
        // assert on that explicitly via assertThat(system).isNull().
        if (system == null) return null;
        assertThat(system).isInstanceOf(List.class);
        return (List<Map<String, Object>>) system;
    }

    @Test
    void twoSegmentSystemMarksOnlyCacheablePrefixAboveFloor() throws Exception {
        // Case A — tenant-level prefix (cacheable, above floor) + session
        // suffix (uncacheable). Only the prefix block carries cache_control.
        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(256)
                .systemSegments(List.of(
                        seg(aboveCacheFloor("TENANT-PREFIX"), true),
                        seg("Today is 2026-05-07; the user is logged in as alice.", false)
                ))
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hello").build()))
                .build();

        List<Map<String, Object>> blocks = systemBlocksOf(req);
        assertThat(blocks).as("expect 2 system content blocks").hasSize(2);

        assertThat(blocks.get(0))
                .as("cacheable prefix above 1024 tokens MUST carry cache_control")
                .containsEntry("type", "text")
                .containsKey("cache_control");
        Map<String, Object> cc = (Map<String, Object>) blocks.get(0).get("cache_control");
        assertThat(cc).containsEntry("type", "ephemeral");

        assertThat(blocks.get(1))
                .as("uncacheable session suffix MUST NOT carry cache_control")
                .containsEntry("type", "text")
                .doesNotContainKey("cache_control");
    }

    @Test
    void cacheablePrefixBelowFloorShipsWithoutCacheControl() throws Exception {
        // Case B — cacheable prefix is below the 1024-token floor. The
        // segment still ships as plain text but WITHOUT cache_control,
        // because Anthropic would silently ignore a sub-1024-token marker
        // and we don't want to advertise cache slots that never hit.
        String shortPrefix = "You are a helpful assistant."; // ~28 chars ≪ 4096
        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(256)
                .systemSegments(List.of(
                        seg(shortPrefix, true),
                        seg("And the user is alice.", false)
                ))
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hi").build()))
                .build();

        List<Map<String, Object>> blocks = systemBlocksOf(req);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0))
                .as("sub-floor cacheable segment must drop cache_control marker")
                .containsEntry("type", "text")
                .containsEntry("text", shortPrefix)
                .doesNotContainKey("cache_control");
        assertThat(blocks.get(1)).doesNotContainKey("cache_control");
    }

    @Test
    void threeSegmentsEmitTwoCacheControlMarkers() throws Exception {
        // Case C — prefix + middle + suffix; both prefix and middle are
        // cacheable+above-floor, suffix is uncacheable. The wire shape must
        // carry 2 cache_control markers; Anthropic treats each as the end of
        // a separate cache entry, so the operator gets two-tier caching
        // (e.g. tenant template + agent skills + per-session details).
        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(256)
                .systemSegments(List.of(
                        seg(aboveCacheFloor("TENANT"), true),
                        seg(aboveCacheFloor("AGENT-SKILLS"), true),
                        seg("Session-specific details here.", false)
                ))
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hi").build()))
                .build();

        List<Map<String, Object>> blocks = systemBlocksOf(req);
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).containsKey("cache_control");
        assertThat(blocks.get(1)).containsKey("cache_control");
        assertThat(blocks.get(2)).doesNotContainKey("cache_control");

        long markerCount = blocks.stream().filter(b -> b.containsKey("cache_control")).count();
        assertThat(markerCount)
                .as("exactly 2 cache_control markers expected for 3-segment case")
                .isEqualTo(2L);
    }

    @Test
    void singleStringSystemPromptStaysByteIdenticalForBackCompat() throws Exception {
        // Regression — when systemSegments is null/empty, the provider must
        // fall back to the legacy single-string path: one block with
        // cache_control regardless of length. Existing callers (20+ services
        // listed at survey time) use only systemPrompt, so this path MUST
        // stay byte-identical with the pre-B.3-advanced baseline.
        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(256)
                .systemPrompt("You are an enterprise assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hi").build()))
                .build();

        List<Map<String, Object>> blocks = systemBlocksOf(req);
        assertThat(blocks).as("legacy path must still emit a single block").hasSize(1);
        assertThat(blocks.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "You are an enterprise assistant.")
                .containsKey("cache_control");

        // And serialize cleanly via the wired ObjectMapper (no Jackson errors).
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(blocks);
        assertThat(json).contains("\"cache_control\":{\"type\":\"ephemeral\"}");
    }
}
