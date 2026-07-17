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
import java.util.LinkedHashMap;
import java.util.List;
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
    static final String UNPUBLISH = "faq:unpublish";
    static final String EXTRACT = "faq:extract";

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
    private final FaqCandidateService faqCandidateService;

    public FaqCandidateCommandHandler(KbTextIngestService kbTextIngestService,
                                      FaqCandidateService faqCandidateService) {
        this.kbTextIngestService = kbTextIngestService;
        this.faqCandidateService = faqCandidateService;
    }

    @Override
    public String getCommandType() {
        return PUBLISH;
    }

    @Override
    public boolean supports(String commandType) {
        return PUBLISH.equals(commandType) || UNPUBLISH.equals(commandType) || EXTRACT.equals(commandType);
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(PUBLISH, UNPUBLISH, EXTRACT);
    }

    @Override
    public boolean requiresDslPersistence(String commandType,
                                          Map<String, Object> execConfig,
                                          CommandExecuteRequest request) {
        return false;
    }

    @Override
    public Object execute(CommandContext context) {
        if (EXTRACT.equals(context.commandType())) {
            return extract(context);
        }
        if (UNPUBLISH.equals(context.commandType())) {
            return unpublish(context);
        }
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

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("faq_status", STATUS_PUBLISHED);
        patch.put("faq_kb_document_pid", docPid);
        patch.put("faq_reviewed_at", Instant.now().toString());
        Map<String, Object> updated = data.update(FaqCandidateService.MODEL, candidatePid, patch);

        log.info("[faq-publish] tenant={} candidate={} -> kb={} doc={}",
                context.tenantId(), candidatePid, kbPid, docPid);
        return updated;
    }

    /**
     * Retract a published FAQ: remove its document from the knowledge base so it stops being
     * recalled, and return the candidate to {@code approved} so it can be re-published later.
     *
     * <p>Removing the document is the load-bearing half — flipping the status alone would leave the
     * answer live in the knowledge base while the console claimed it was pulled. The candidate pid is
     * the ingest source id, so {@code remove} drops exactly the document {@code publish} created (or
     * a re-publish would have replaced), by the same key.
     */
    private Map<String, Object> unpublish(CommandContext context) {
        DataAccessor data = context.dataAccessor();
        if (data == null) {
            throw new BusinessException(ResponseCode.SystemError, "DataAccessor unavailable for faq:unpublish");
        }
        String candidatePid = context.recordId();
        if (candidatePid == null || candidatePid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "A FAQ candidate is required to unpublish");
        }
        Map<String, Object> candidate = data.getById(FaqCandidateService.MODEL, candidatePid);
        if (candidate == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "FAQ candidate not found: " + candidatePid);
        }
        String status = str(candidate.get("faq_status"));
        if (!STATUS_PUBLISHED.equals(status)) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Only a published FAQ candidate can be unpublished (this one is " + status + ")");
        }
        String kbPid = str(candidate.get("faq_target_kb_id"));

        boolean removed = kbTextIngestService.remove(context.tenantId(), kbPid, SOURCE_TYPE, candidatePid);

        // Back to approved, and forget the document pid — the document is gone, so a dangling
        // reference to it would be a lie the detail page would then render.
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("faq_status", STATUS_APPROVED);
        patch.put("faq_kb_document_pid", null);
        Map<String, Object> updated = data.update(FaqCandidateService.MODEL, candidatePid, patch);

        log.info("[faq-unpublish] tenant={} candidate={} kb={} removed={}",
                context.tenantId(), candidatePid, kbPid, removed);
        return updated;
    }

    /**
     * Distil a conversation into candidates, driven from the conversation queue's row action.
     *
     * <p>The record this command targets is a conversation, not a candidate: {@code faq_source_conversation}
     * is a metadata-only model over {@code ab_im_conversation} (skipTableCreation), so {@code recordId}
     * is the conversation pid. Nothing is written to the conversation — the command's whole effect is
     * the candidates it creates, which is why {@link #requiresDslPersistence} is false here too.
     */
    private Object extract(CommandContext context) {
        String conversationPid = context.recordId();
        if (conversationPid == null || conversationPid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "A conversation is required to distil FAQs from");
        }
        Map<String, Object> payload = context.payload() != null ? context.payload() : Map.of();
        String targetKbPid = str(payload.get("faq_target_kb_id"));
        if (targetKbPid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam,
                    "A target knowledge base is required — a candidate with nowhere to publish is not reviewable");
        }

        List<Map<String, Object>> created;
        try {
            created = faqCandidateService.extractFromConversation(context.tenantId(), conversationPid, targetKbPid);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // The LlmProvider contract throws checked Exception; surface the provider's message rather
            // than a bare 500, because "the model is unreachable" is the reviewer's problem to see.
            throw new BusinessException(ResponseCode.SystemError,
                    "FAQ distillation failed: " + e.getMessage());
        }

        log.info("[faq-extract] tenant={} conversation={} -> {} candidate(s)",
                context.tenantId(), conversationPid, created.size());
        // Return the count and the pids, never the rows themselves. A dynamic-model row carries
        // tenant_id / created_by / updated_by, and this is a public response — the dual-id contract
        // says those never cross the boundary. The console re-reads the queue anyway; it needs to
        // know how many landed, not what is in them.
        //
        // Zero is a legitimate verdict ("nothing reusable in this conversation"), not a failure.
        List<String> pids = created.stream()
                .map(c -> c.get("pid"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
        return Map.of("conversationPid", conversationPid,
                "createdCount", created.size(),
                "candidatePids", pids);
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
