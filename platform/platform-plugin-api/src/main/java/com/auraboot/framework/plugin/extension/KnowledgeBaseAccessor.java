package com.auraboot.framework.plugin.extension;

import java.util.Map;

/**
 * Plugin-safe facade for ingesting text documents into a platform-managed RAG
 * knowledge base.
 *
 * <p>Plugins (e.g. the crawler control plane landing crawled documents into a KB)
 * use this accessor instead of depending on RAG service beans directly. The platform
 * owns chunking, embedding-provider configuration (CloudConfig), vector storage
 * (pgvector / {@code ab_kb_chunk}) and tenant scoping.
 *
 * <p>Injected into plugin {@code BackgroundComponentExtension} / handler beans by the
 * host: the platform impl is a Spring {@code @Service}, autowired BY TYPE into plugin
 * extensions (the same mechanism the host uses for {@code BackgroundDataAccessor}). A
 * plugin simply declares {@code @Autowired KnowledgeBaseAccessor}.
 */
public interface KnowledgeBaseAccessor {

    /**
     * Ingest one text document into a knowledge base: chunk &rarr; embed &rarr; store
     * vectors. Idempotent per {@code (sourceType, sourceId)} &mdash; re-ingesting the
     * same source replaces that source's prior document + chunks. The whole write
     * sequence is transactional.
     *
     * @param tenantId   owning tenant
     * @param kbPid      target knowledge base pid (must already exist)
     * @param sourceType caller domain tag, e.g. {@code "crawler"}
     * @param sourceId   stable source identity for idempotent re-ingest, e.g. a crawl URL id
     * @param docName    human-readable document title (falls back to {@code sourceType:sourceId})
     * @param text       full document text (blank text is a no-op)
     * @param metadata   optional advisory metadata (reserved; may be null)
     * @return the created/updated KB document pid, or {@code null} if {@code kbPid} is
     *         unknown or {@code text} is blank
     */
    String ingestText(long tenantId, String kbPid, String sourceType, String sourceId,
                      String docName, String text, Map<String, Object> metadata) throws Exception;
}
