package com.auraboot.framework.meta.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiFieldLockSupport} — the server-side guard that keeps
 * an AI fill from ever producing values for AI-locked fields (D5).
 */
class AiFieldLockSupportTest {

    @Test
    void stripLockedFields_removesLockedKeysKeepingTheRest() {
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("wd_req_reason", "family matter");
        generated.put("wd_req_type", "annual");
        generated.put("wd_req_days", 2);

        Map<String, Object> result =
                AiFieldLockSupport.stripLockedFields(generated, List.of("wd_req_reason"));

        assertThat(result).containsOnlyKeys("wd_req_type", "wd_req_days");
        assertThat(result).doesNotContainKey("wd_req_reason");
    }

    @Test
    void stripLockedFields_returnsAllWhenNothingLocked() {
        Map<String, Object> generated = Map.of("a", 1, "b", 2);
        assertThat(AiFieldLockSupport.stripLockedFields(generated, List.of()))
                .containsOnlyKeys("a", "b");
        assertThat(AiFieldLockSupport.stripLockedFields(generated, null))
                .containsOnlyKeys("a", "b");
    }

    @Test
    void stripLockedFields_handlesNullFieldsSafely() {
        assertThat(AiFieldLockSupport.stripLockedFields(null, List.of("x"))).isEmpty();
    }

    @Test
    void stripLockedFields_doesNotMutateTheInputMap() {
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("locked", "v");
        generated.put("free", "w");

        AiFieldLockSupport.stripLockedFields(generated, List.of("locked"));

        assertThat(generated).containsKey("locked");
    }
}
