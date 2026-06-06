package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.rag.service.KbTextIngestService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G3 (B): the plugin-facing accessor is a thin delegate to KbTextIngestService. */
class KnowledgeBaseAccessorImplTest {

    @Test
    void ingestText_delegatesToService() throws Exception {
        KbTextIngestService svc = mock(KbTextIngestService.class);
        when(svc.ingestText(7L, "KB1", "crawler", "u1", "Doc", "body")).thenReturn("DOC-PID");
        KnowledgeBaseAccessorImpl accessor = new KnowledgeBaseAccessorImpl(svc);

        String pid = accessor.ingestText(7L, "KB1", "crawler", "u1", "Doc", "body", Map.of("k", "v"));

        assertEquals("DOC-PID", pid);
        verify(svc).ingestText(eq(7L), eq("KB1"), eq("crawler"), eq("u1"), eq("Doc"), eq("body"));
    }
}
