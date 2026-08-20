package com.auraboot.framework.promotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionThreeWayMergeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromotionThreeWayMergeService service =
            new PromotionThreeWayMergeService(objectMapper);

    @Test
    void mergesIndependentStableIdChanges() throws Exception {
        ObjectNode base = json("""
                {"blocks":[{"id":"a","props":{"density":"normal"}},
                            {"id":"b","props":{"title":"Base"}}]}
                """);
        ObjectNode incoming = json("""
                {"blocks":[{"id":"a","props":{"density":"compact"}},
                            {"id":"b","props":{"title":"Base"}}]}
                """);
        ObjectNode local = json("""
                {"blocks":[{"id":"a","props":{"density":"normal"}},
                            {"id":"b","props":{"title":"Local"}}]}
                """);

        ObjectNode merged = service.merge(base, incoming, local);

        assertThat(merged.at("/blocks/0/props/density").asText()).isEqualTo("compact");
        assertThat(merged.at("/blocks/1/props/title").asText()).isEqualTo("Local");
    }

    @Test
    void rejectsConflictingStableIdProperty() throws Exception {
        ObjectNode base = json("{" + "\"blocks\":[{\"id\":\"a\",\"title\":\"Base\"}]}");
        ObjectNode incoming = json("{" + "\"blocks\":[{\"id\":\"a\",\"title\":\"Incoming\"}]}");
        ObjectNode local = json("{" + "\"blocks\":[{\"id\":\"a\",\"title\":\"Local\"}]}");

        assertThatThrownBy(() -> service.merge(base, incoming, local))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion.drift.rebase-conflict")
                .hasMessageContaining("/@a/title");
    }

    private ObjectNode json(String value) throws Exception {
        return (ObjectNode) objectMapper.readTree(value);
    }
}
