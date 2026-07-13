package com.auraboot.framework.faq;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side of the FAQ loop: which conversations are there to distil, and what was actually said
 * in one.
 *
 * <p>Reads {@code ab_im_*} and writes nothing to it. It lives here rather than in
 * {@code framework/im} on purpose: that module is owned by the embeddable-channel track and is
 * being reworked, and its public boundary still speaks internal ids. Everything this service
 * returns is keyed by conversation <b>pid</b>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqConversationQueryService {

    /** A transcript longer than this is not reviewable in a drawer; page it if it ever matters. */
    private static final int MAX_TRANSCRIPT_MESSAGES = 500;

    private final ImConversationMapper conversationMapper;
    private final ImMessageMapper messageMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Conversations in this tenant, newest activity first.
     *
     * @param type optional filter on conversation type (group / bot / private / object)
     */
    public FaqConversationView.Page listConversations(Long tenantId, String type, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 200));
        int page = Math.max(1, pageNum);

        LambdaQueryWrapper<ImConversation> where = new LambdaQueryWrapper<ImConversation>()
                .eq(ImConversation::getTenantId, tenantId);
        if (type != null && !type.isBlank()) {
            where.eq(ImConversation::getType, type);
        }
        Long total = conversationMapper.selectCount(where);

        where.orderByDesc(ImConversation::getLastMessageAt).orderByDesc(ImConversation::getId);
        where.last("LIMIT " + size + " OFFSET " + ((page - 1) * size));
        List<ImConversation> rows = conversationMapper.selectList(where);

        Map<String, Long> candidateCounts = countCandidatesByConversation(tenantId, rows);

        List<FaqConversationView.Item> items = new ArrayList<>(rows.size());
        for (ImConversation c : rows) {
            items.add(new FaqConversationView.Item(
                    c.getPid(),
                    c.getName(),
                    c.getType(),
                    c.getMaxSeq() == null ? 0L : c.getMaxSeq(),
                    c.getLastMessageAt(),
                    candidateCounts.getOrDefault(c.getPid(), 0L)));
        }
        return new FaqConversationView.Page(items, total == null ? 0L : total, page, size);
    }

    /** The transcript, in the order it happened. */
    public List<FaqConversationView.Message> listMessages(Long tenantId, String conversationPid) {
        ImConversation conversation = conversationMapper.findByPid(conversationPid, tenantId);
        if (conversation == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Conversation not found: " + conversationPid);
        }

        List<ImMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ImMessage>()
                .eq(ImMessage::getConversationId, conversation.getId())
                .eq(ImMessage::getTenantId, tenantId)
                .eq(ImMessage::getRecalled, false)
                .orderByAsc(ImMessage::getSeq)
                .last("LIMIT " + MAX_TRANSCRIPT_MESSAGES));

        List<FaqConversationView.Message> out = new ArrayList<>(messages.size());
        for (ImMessage m : messages) {
            out.add(new FaqConversationView.Message(
                    m.getSeq() == null ? 0L : m.getSeq(),
                    // Same labelling the distiller sees, so the reviewer is reading what the model read.
                    "agent".equals(m.getSenderType()) ? "Support" : "Customer",
                    m.getContent(),
                    m.getCreatedAt()));
        }
        return out;
    }

    /**
     * How many candidates each conversation has already produced.
     *
     * <p>Straight SQL against {@code mt_faq_candidate} rather than N per-conversation queries: the
     * queue is the first thing a reviewer sees, and one round-trip per row would make it crawl.
     * The table is created by the plugin's model publish, so it can legitimately be absent on a
     * stack where {@code core-faq-loop} was never imported — that is not an error, it just means
     * nothing has been distilled yet.
     */
    private Map<String, Long> countCandidatesByConversation(Long tenantId, List<ImConversation> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> pids = rows.stream().map(ImConversation::getPid).filter(p -> p != null).toList();
        if (pids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", pids.stream().map(p -> "?").toList());
        Object[] args = new Object[pids.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < pids.size(); i++) {
            args[i + 1] = pids.get(i);
        }

        Map<String, Long> counts = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT faq_source_conversation_pid AS pid, COUNT(*) AS n FROM mt_faq_candidate "
                            + "WHERE tenant_id = ? AND faq_source_conversation_pid IN (" + placeholders + ") "
                            + "GROUP BY faq_source_conversation_pid",
                    rs -> {
                        counts.put(rs.getString("pid"), rs.getLong("n"));
                    },
                    args);
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            log.debug("[faq-conversations] mt_faq_candidate not present — nothing distilled yet");
            return Map.of();
        }
        return counts;
    }
}
