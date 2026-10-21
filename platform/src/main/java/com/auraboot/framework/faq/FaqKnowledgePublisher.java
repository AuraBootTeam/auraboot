package com.auraboot.framework.faq;

/** Knowledge document lifecycle required by approved FAQ publication. */
public interface FaqKnowledgePublisher {
    String ingestText(long tenantId, String kbPid, String sourceType, String sourceId, String docName, String text);
    boolean remove(long tenantId, String kbPid, String sourceType, String sourceId);
}
