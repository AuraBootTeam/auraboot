package com.auraboot.framework.agent.integration.faq;

import com.auraboot.framework.faq.FaqKnowledgePublisher;
import com.auraboot.framework.rag.service.KbTextIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentFaqKnowledgePublisher implements FaqKnowledgePublisher {
    private final KbTextIngestService knowledge;
    @Override public String ingestText(long tenantId, String kbPid, String sourceType, String sourceId, String docName, String text) {
        return knowledge.ingestText(tenantId, kbPid, sourceType, sourceId, docName, text);
    }
    @Override public boolean remove(long tenantId, String kbPid, String sourceType, String sourceId) {
        return knowledge.remove(tenantId, kbPid, sourceType, sourceId);
    }
}
