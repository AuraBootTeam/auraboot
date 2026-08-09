package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringPageSnapshotFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthoringPageSnapshotFactory factory =
            new AuthoringPageSnapshotFactory(objectMapper);

    @Test
    void checksumIsStableAcrossJsonObjectKeyOrder() throws Exception {
        var first = objectMapper.readTree("""
                {"pageKey":"orders","blocks":[{"id":"table","props":{"b":2,"a":1}}]}
                """);
        var reordered = objectMapper.readTree("""
                {"blocks":[{"props":{"a":1,"b":2},"id":"table"}],"pageKey":"orders"}
                """);

        assertThat(factory.checksum(first)).isEqualTo(factory.checksum(reordered));
    }
}
