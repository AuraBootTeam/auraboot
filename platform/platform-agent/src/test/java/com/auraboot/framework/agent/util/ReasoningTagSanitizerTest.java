package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReasoningTagSanitizer")
class ReasoningTagSanitizerTest {

    @Test
    @DisplayName("complete think blocks are removed from final visible text")
    void completeThinkBlocksAreRemovedFromFinalVisibleText() {
        String cleaned = ReasoningTagSanitizer.stripComplete(
                "Before <think>hidden procurement reasoning</think> after.");

        assertThat(cleaned).isEqualTo("Before  after.");
    }

    @Test
    @DisplayName("streaming chunks hide split think tags and their inner content")
    void streamingChunksHideSplitThinkTagsAndInnerContent() {
        ReasoningTagSanitizer sanitizer = new ReasoningTagSanitizer();

        String first = sanitizer.filterChunk("Visible <thi");
        String second = sanitizer.filterChunk("nk>hidden");
        String third = sanitizer.filterChunk(" reasoning</thi");
        String fourth = sanitizer.filterChunk("nk> answer");
        String tail = sanitizer.finish();

        assertThat(first).isEqualTo("Visible ");
        assertThat(second).isEmpty();
        assertThat(third).isEmpty();
        assertThat(fourth).isEqualTo(" answer");
        assertThat(tail).isEmpty();
    }
}
