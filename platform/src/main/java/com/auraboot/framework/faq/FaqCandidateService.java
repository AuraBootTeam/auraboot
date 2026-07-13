package com.auraboot.framework.faq;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a conversation into reviewable FAQ candidates.
 *
 * <p>Renders the conversation into a transcript, hands it to
 * {@link ConversationFaqExtractionService}, and stores whatever comes back as
 * {@code faq_candidate} rows in {@code draft}. Nothing reaches the knowledge base here —
 * a human approves and publishes, which is the whole point of the candidate table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqCandidateService {

    public static final String MODEL = "faq_candidate";

    static final String STATUS_DRAFT = "draft";

    private final ImConversationMapper conversationMapper;
    private final ImMessageMapper messageMapper;
    private final ConversationFaqExtractionService extractionService;
    private final DynamicDataService dynamicDataService;

    /**
     * Distil FAQ candidates from one conversation.
     *
     * <p>Re-running this on the same conversation is safe. Existing {@code draft} candidates
     * are machine output and get regenerated; candidates a human already approved, published
     * or rejected are left alone, and any pair whose question matches one of them is skipped
     * rather than re-proposed.
     *
     * @return the candidates created by this run, possibly empty
     */
    @Transactional
    public List<Map<String, Object>> extractFromConversation(Long tenantId,
                                                             String conversationPid,
                                                             String targetKbPid) throws Exception {
        ImConversation conversation = conversationMapper.findByPid(conversationPid, tenantId);
        if (conversation == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Conversation not found: " + conversationPid);
        }
        if (targetKbPid == null || targetKbPid.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam,
                    "A target knowledge base is required — a candidate with nowhere to publish is not reviewable");
        }

        List<ImMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ImMessage>()
                .eq(ImMessage::getConversationId, conversation.getId())
                .eq(ImMessage::getTenantId, tenantId)
                .eq(ImMessage::getRecalled, false)
                .orderByAsc(ImMessage::getSeq));
        if (messages.isEmpty()) {
            log.info("[faq-extract] conversation={} has no messages — nothing to distil", conversationPid);
            return List.of();
        }

        List<Map<String, Object>> existing = findCandidates(conversationPid);
        Set<String> decidedQuestions = existing.stream()
                .filter(c -> !STATUS_DRAFT.equals(str(c.get("faq_status"))))
                .map(c -> normalize(str(c.get("faq_question"))))
                .collect(Collectors.toSet());

        // Drafts are this service's own previous output — replace them rather than pile up.
        for (Map<String, Object> stale : existing) {
            if (STATUS_DRAFT.equals(str(stale.get("faq_status")))) {
                dynamicDataService.delete(MODEL, str(stale.get("pid")));
            }
        }

        List<ExtractedFaq> extracted = extractionService.extract(tenantId, renderTranscript(messages));
        String seqRange = messages.get(0).getSeq() + "-" + messages.get(messages.size() - 1).getSeq();

        List<Map<String, Object>> created = new ArrayList<>();
        for (ExtractedFaq faq : extracted) {
            if (decidedQuestions.contains(normalize(faq.question()))) {
                log.info("[faq-extract] conversation={} skipping already-decided question: {}",
                        conversationPid, faq.question());
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("faq_question", faq.question());
            data.put("faq_answer", faq.answer());
            // Stored as a percentage: the model speaks in 0-1, but a reviewer reads a column, and a
            // column showing "1" is not a confidence, it is a riddle. Left absent when the model did
            // not report one — the column then shows "—" rather than claiming 0%.
            if (faq.confidence() != null) {
                data.put("faq_confidence", Math.round(faq.confidence() * 100.0));
            }
            data.put("faq_status", STATUS_DRAFT);
            data.put("faq_source_conversation_pid", conversationPid);
            data.put("faq_source_seq_range", seqRange);
            data.put("faq_target_kb_id", targetKbPid);
            created.add(dynamicDataService.create(MODEL, data));
        }

        log.info("[faq-extract] conversation={} messages={} extracted={} created={}",
                conversationPid, messages.size(), extracted.size(), created.size());
        return created;
    }

    /** All candidates distilled from one conversation, whatever their review status. */
    public List<Map<String, Object>> findCandidates(String conversationPid) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(1000)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName("faq_source_conversation_pid")
                        .operator(QueryCondition.Operator.EQ)
                        .value(conversationPid)
                        .build()))
                .build();
        PaginationResult<Map<String, Object>> page = dynamicDataService.list(MODEL, request);
        return page == null || page.getRecords() == null ? List.of() : page.getRecords();
    }

    /**
     * Renders the conversation the way a human reads it. Sender role is spelled out because
     * "who said it" is what separates a question from its answer; without it the model has to
     * guess which turn is the agent's, and it guesses wrong on ambiguous threads.
     */
    static String renderTranscript(List<ImMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ImMessage m : messages) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append('[').append(m.getSeq()).append("] ")
                    .append("agent".equals(m.getSenderType()) ? "Support" : "Customer")
                    .append(": ").append(content.trim()).append('\n');
        }
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String normalize(String question) {
        return question.trim().toLowerCase(Locale.ROOT);
    }
}
