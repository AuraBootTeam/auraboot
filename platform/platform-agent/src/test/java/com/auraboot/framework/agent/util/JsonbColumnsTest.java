package com.auraboot.framework.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the canonical JSONB-column extractor. */
class JsonbColumnsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void string_isReturnedAsIs() {
        assertEquals("[\"a\",\"b\"]", JsonbColumns.toJsonText("[\"a\",\"b\"]", mapper));
    }

    @Test
    void pgobject_usesToString_notWrapperSerialization() throws Exception {
        // This is the bug the util fixes: a JSONB column from a generic query is a
        // PGobject. writeValueAsString(pg) would yield {"type":"jsonb","value":..};
        // toString() yields the actual JSON.
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue("[\"dsl.query\"]");
        String json = JsonbColumns.toJsonText(pg, mapper);
        assertEquals("[\"dsl.query\"]", json);
        // and it round-trips into the intended type
        List<String> list = mapper.readValue(json, mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        assertEquals(List.of("dsl.query"), list);
    }

    @Test
    void parsedListOrMap_isReserialized() {
        assertEquals("[\"a\",\"b\"]", JsonbColumns.toJsonText(List.of("a", "b"), mapper));
        assertEquals("{\"k\":\"v\"}", JsonbColumns.toJsonText(Map.of("k", "v"), mapper));
    }

    @Test
    void nullBlankAndLiteralNull_returnNull() {
        assertNull(JsonbColumns.toJsonText(null, mapper));
        assertNull(JsonbColumns.toJsonText("", mapper));
        assertNull(JsonbColumns.toJsonText("   ", mapper));
        assertNull(JsonbColumns.toJsonText("null", mapper));
        PGobject empty = new PGobject();
        empty.setType("jsonb");
        assertNull(JsonbColumns.toJsonText(empty, mapper)); // value==null → toString "null"-ish
    }
}
