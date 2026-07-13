package com.auraboot.framework.faq;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.rag.service.KbTextIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Publishes an approved FAQ candidate into its target knowledge base.
 *
 * <p>An in-platform Spring bean rather than a PF4J extension, so {@code core-faq-loop} stays
 * a config-only plugin with no jar to build, copy and restart around. It binds by command
 * code — the platform routes {@code faq:publish} here because {@link #supports} claims it —
 * exactly like {@code SiteKeyCommandHandler}. approve / reject / update_qa need no handler at
 * all; they are declarative state transitions in the plugin's commands.json.
 *
 * <p>{@link #requiresDslPersistence} returns false: this handler is the sole writer, because
 * the write-back and the status flip have to succeed or fail together. A candidate marked
 * published whose document never made it into the knowledge base is the one inconsistency
 * that would quietly break the loop.
 */
@Slf4j
@Component
public class FaqCandidateCommandHandler implements CommandHandlerExtension {

    static final String PUBLISH = "faq:publish";

    /**
     * The logical source recorded on the knowledge-base document. This value is only honoured
     * end-to-end because it appears in BOTH the chk_doc_source CHECK constraint AND
     * {@code KbTextIngestService.DB_SOURCE_TYPES} — a value in the constraint alone is
     * silently rewritten to internal_doc.
     */
    static final String SOURCE_TYPE = "conversation";

    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_PUBLISHED = "published";

    private final KbTextIngestService kbTextIngestService;

    public FaqCandidateCommandHandler(KbTextIngestService kbTextIngestService) {
        this.kbTextIngestService = kbTextIngestService;
    }

    @Override
    public String getCommandType() {
        return PUBLISH;
    }

    @Override
    public boolean supports(String commandType) {
        return PUBLISH.equals(commandType);
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(PUBLISH);
    }

    @Override
    public boolean requiresDslPersistence(String commandType,
                                          Map<String, Object> execConfig,
                                          CommandExecuteRequest request) {
        return false;
    }

    @Override
    public Object execute(CommandContext context) {
        if (!PUBLISH.equals(context.commandType())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Unsupported FAQ command: " + context.commandType());
        }
        DataAccessor data = context.dataAccessor();
        if (data == null) {
            throw new BusinessException(ResponseCode.SystemError, "DataAccessor unavailable for faq:publish");
        }

        String candidatePid = context.recordId();
        if (candidatePid == null || candidatePid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "A FAQ candidate is required to publish");
        }

        Map<String, Object> candidate = data.getById(FaqCandidateService.MODEL, candidatePid);
        if (candidate == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "FAQ candidate not found: " + candidatePid);
        }

        String status = str(candidate.get("faq_status"));
        if (!STATUS_APPROVED.equals(status) && !STATUS_PUBLISHED.equals(status)) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only an approved FAQ candidate can be published (this one is " + status + ")");
        }

        String kbPid = str(candidate.get("faq_target_kb_id"));
        if (kbPid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam,
                    "FAQ candidate " + candidatePid + " has no target knowledge base");
        }
        String question = str(candidate.get("faq_question"));
        String answer = str(candidate.get("faq_answer"));
        if (question.isBlank() || answer.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam,
                    "FAQ candidate " + candidatePid + " is missing its question or answer");
        }

        // The candidate pid is the ingest source id, which is what makes re-publishing idempotent:
        // KbTextIngestService drops the prior document for the same (sourceType, sourceId) before
        // inserting, so a candidate can never end up with two documents in the knowledge base.
        String docPid = kbTextIngestService.ingestText(
                context.tenantId(), kbPid, SOURCE_TYPE, candidatePid, docName(question), body(question, answer));
        if (docPid == null) {
            throw new BusinessException(ResponseCode.SystemError,
                    "Knowledge base " + kbPid + " rejected the FAQ document (unknown kb, or empty text)");
        }

        Map<String, Object> updated = data.update(FaqCandidateService.MODEL, candidatePid, Map.of(
                "faq_status", STATUS_PUBLISHED,
                "faq_kb_document_pid", docPid,
                "faq_reviewed_at", Instant.now().toString()));

        log.info("[faq-publish] tenant={} candidate={} -> kb={} doc={}",
                context.tenantId(), candidatePid, kbPid, docPid);
        return updated;
    }

    /** What the reviewer will see in the knowledge-base document list. */
    private static String docName(String question) {
        String trimmed = question.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "...";
    }

    /**
     * Both halves go into the document body. Embedding the question alongside the answer is what
     * lets retrieval match a customer who phrases the question differently — an answer alone
     * embeds the topic but not the ask.
     */
    private static String body(String question, String answer) {
        return "Q: " + question.trim() + "\n\nA: " + answer.trim();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
