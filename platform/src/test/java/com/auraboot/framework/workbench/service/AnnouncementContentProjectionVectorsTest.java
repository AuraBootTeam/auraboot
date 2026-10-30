package com.auraboot.framework.workbench.service;

import com.auraboot.framework.plugin.extension.AnnouncementDocumentCanonicalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnnouncementContentProjectionVectorsTest {
    @Test void fullSnapshotHashesVaryWhileTheSharedDocumentDigestDoesNot() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var codec=new AnnouncementCodec(json);
        var vector=json.readTree(Files.readString(Path.of("platform-plugin-api/src/test/resources/announcement-sealed-content/vectors.json")))
                .path("vectors").get(0);
        Map<String,Object> a=json.convertValue(vector.path("snapshotA"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        Map<String,Object> b=json.convertValue(vector.path("snapshotB"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        assertEquals(vector.path("announcementSnapshotHashA").textValue(),codec.hash(a));
        assertEquals(vector.path("announcementSnapshotHashB").textValue(),codec.hash(b));
        assertNotEquals(codec.hash(a),codec.hash(b));
        for(var snapshot:List.of(a,b)) {
            var document=new AnnouncementDocumentCanonicalizer.Document((String)snapshot.get("title"),(String)snapshot.get("content"),List.of());
            assertEquals(vector.path("documentDigest").textValue(),AnnouncementDocumentCanonicalizer.encode(document).documentDigest());
            assertNotEquals(codec.hash(snapshot),AnnouncementDocumentCanonicalizer.encode(document).documentDigest());
        }
    }
}
