package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiProtocolHashTest {
    private final OpenApiProtocolHash hash = new OpenApiProtocolHash(new ObjectMapper().findAndRegisterModules(), new OpenPlatformSecretCodec());
    @Test void objectOrderAndAuthoritySetOrderDoNotDependOnJvmIteration() {
        var first = new LinkedHashMap<String, Object>(); first.put("scope", "devices.read"); first.put("effects", List.of("state", "assignee"));
        var second = new LinkedHashMap<String, Object>(); second.put("effects", List.of("assignee", "state")); second.put("scope", "devices.read");
        assertThat(hash.fingerprint(first, true)).isEqualTo(hash.fingerprint(second, true)).hasSize(64);
        assertThat(hash.fingerprint(Map.of("input", Map.of("b", 2, "a", 1)), false))
                .isEqualTo(hash.fingerprint(Map.of("input", new TreeMap<>(Map.of("a", 1, "b", 2))), false));
    }
    @Test void requestArraysAndChangedScalarValuesRemainDifferentIntents() {
        assertThat(hash.fingerprint(Map.of("input", List.of("first", "second")), false))
                .isNotEqualTo(hash.fingerprint(Map.of("input", List.of("second", "first")), false));
        assertThat(hash.fingerprint(Map.of("value", "A"), true)).isNotEqualTo(hash.fingerprint(Map.of("value", "B"), true));
        assertThat(hash.fingerprint(null, false)).hasSize(64);
    }
}
