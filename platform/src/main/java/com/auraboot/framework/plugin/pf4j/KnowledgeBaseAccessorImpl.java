package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.KnowledgeBaseAccessor;
import com.auraboot.framework.rag.service.KbTextIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Platform-side impl of {@link KnowledgeBaseAccessor}. A Spring {@code @Service} so the
 * host autowires it BY TYPE into plugin {@code BackgroundComponentExtension} beans
 * (same mechanism as {@code BackgroundDataAccessorImpl}); no explicit registration.
 *
 * <p>Thin facade: delegates the chunk/embed/store sequence to {@link KbTextIngestService}
 * (which lives in {@code rag.service} so it can reach package-private KB lookups). This
 * keeps RAG implementation details out of the plugin API surface.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessorImpl implements KnowledgeBaseAccessor {

    private final KbTextIngestService ingestService;

    @Override
    public String ingestText(long tenantId, String kbPid, String sourceType, String sourceId,
                             String docName, String text, Map<String, Object> metadata) {
        // Plugin callers (e.g. the crawler CrawledDocumentUpsertConsumer) invoke this from a
        // Kafka consumer thread that has NO MetaContext, but KbTextIngestService → KnowledgeBaseService
        // runs tenant-scoped DB queries that require it ("MetaContext not initialized for current
        // thread"). Establish a system tenant context from the tenantId we were given, and clear it
        // afterward — but only if we created it, so request-thread callers keep their own context.
        boolean owns = !MetaContext.exists();
        if (owns) {
            MetaContext.setSystemTenantContext(tenantId);
        }
        try {
            return ingestService.ingestText(tenantId, kbPid, sourceType, sourceId, docName, text);
        } finally {
            if (owns) {
                MetaContext.clear();
            }
        }
    }
}
