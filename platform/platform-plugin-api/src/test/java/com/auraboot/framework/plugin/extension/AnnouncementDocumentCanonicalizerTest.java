package com.auraboot.framework.plugin.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.auraboot.framework.plugin.extension.AnnouncementDocumentCanonicalizer.*;

class AnnouncementDocumentCanonicalizerTest {
    @Test void sharedLiteralVectorPreservesRawUnicodeAndCrLf() throws Exception {
        try (var input = getClass().getResourceAsStream("/announcement-sealed-content/vectors.json")) {
            var vector = new ObjectMapper().readTree(input).path("vectors").get(0);
            var encoded = encode(new Document(vector.path("title").textValue(), vector.path("body").textValue(), List.of()));
            assertEquals(vector.path("canonicalJson").textValue(), encoded.canonicalJson());
            assertEquals(vector.path("documentDigest").textValue(), encoded.documentDigest());
        }
    }
    @Test void rawTextAndTitleChangesAreSignificant() {
        var original = encode(new Document("Title", " é\r\n<p>正文</p> ", List.of()));
        for (var changed : List.of(new Document("title", " é\r\n<p>正文</p> ", List.of()),
                new Document("Title", " é\r\n<p>正文</p> ", List.of()),
                new Document("Title", " é\n<p>正文</p> ", List.of()),
                new Document("Title", "é\r\n<p>正文</p>", List.of())))
            assertNotEquals(original.documentDigest(), encode(changed).documentDigest());
    }
    @Test void attachmentsAreAsciiSortedDistinctAndContentBound() {
        var a = attachment("01ARZ3NDEKTSV4RRFFQ69G5FAV", 0, "a".repeat(64));
        var b = attachment("01ARZ3NDEKTSV4RRFFQ69G5FAW", Long.MAX_VALUE, "b".repeat(64));
        var encoded = encode(new Document("Title", "Body", List.of(b,a)));
        assertEquals(encoded, encode(new Document("Title", "Body", List.of(a,b))));
        assertTrue(encoded.canonicalJson().contains("\"byteLength\":9223372036854775807"));
        assertThrows(IllegalArgumentException.class, () -> new Document("Title", "Body", List.of(a,a)));
        assertNotEquals(encoded.documentDigest(), encode(new Document("Title", "Body", List.of(a,
                attachment(b.filePid(), b.byteLength(), "c".repeat(64))))).documentDigest());
    }
    @Test void strictScalarsAndEscapingRejectLossyInput() {
        for (String invalid : List.of("\ud800", "\udc00", "x\ud800y"))
            assertThrows(IllegalArgumentException.class, () -> new Document(invalid, "Body", List.of()));
        var encoded = encode(new Document("\"\\\t\n", "", List.of()));
        assertTrue(encoded.canonicalJson().contains("\\\"\\\\\\u0009\\u000a"));
        assertThrows(IllegalArgumentException.class, () -> attachment("not-a-pid", 1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> attachment("01ARZ3NDEKTSV4RRFFQ69G5FAV", -1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> attachment("01ARZ3NDEKTSV4RRFFQ69G5FAV", 1, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new AnnouncementSealedContentAccessor.ExactVersion(
                "01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAW", "a".repeat(64), "unknown"));
    }
    private Attachment attachment(String pid,long size,String hash) {
        return new Attachment(pid, SourceIdentityKind.SEAL_OBSERVED_CONTENT, "d".repeat(64), "file.txt", "text/plain", size, hash);
    }
}
