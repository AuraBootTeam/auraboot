package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rag.service.KbTextIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void ingestText_establishesAndClearsSystemTenantContext_whenThreadHasNone() throws Exception {
        // simulate a Kafka consumer thread: no MetaContext on entry
        MetaContext.clear();
        assertFalse(MetaContext.exists());
        KbTextIngestService svc = mock(KbTextIngestService.class);
        long[] seenTenant = {-1L};
        when(svc.ingestText(eq(7L), eq("KB1"), eq("crawler"), eq("u1"), eq("Doc"), eq("body")))
                .thenAnswer(inv -> {
                    // DB-scoped service runs here — context MUST be set to the tenant we passed
                    assertTrue(MetaContext.exists());
                    seenTenant[0] = MetaContext.getCurrentTenantId();
                    return "DOC-PID";
                });
        KnowledgeBaseAccessorImpl accessor = new KnowledgeBaseAccessorImpl(svc);

        accessor.ingestText(7L, "KB1", "crawler", "u1", "Doc", "body", Map.of());

        assertEquals(7L, seenTenant[0]);          // context carried the tenant
        assertFalse(MetaContext.exists());          // cleared afterward (we created it)
    }

    @Test
    void ingestText_preservesPreexistingContext_whenThreadAlreadyHasOne() throws Exception {
        MetaContext.setSystemTenantContext(999L);   // request-thread caller
        KbTextIngestService svc = mock(KbTextIngestService.class);
        when(svc.ingestText(eq(7L), eq("KB1"), eq("crawler"), eq("u1"), eq("Doc"), eq("body")))
                .thenReturn("DOC-PID");
        KnowledgeBaseAccessorImpl accessor = new KnowledgeBaseAccessorImpl(svc);

        accessor.ingestText(7L, "KB1", "crawler", "u1", "Doc", "body", Map.of());

        // caller's own context is left intact (we did not create it, so we don't clear it)
        assertTrue(MetaContext.exists());
        assertEquals(999L, MetaContext.getCurrentTenantId());
    }
}
