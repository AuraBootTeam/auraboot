package com.auraboot.framework.integration;

import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression for the batched {@code listByUser} (deep-review DR-20260701 W2-perf-002):
 * verifies the four batched queries (selectBatchIds + findMembersByConversationIds +
 * findLastMessagesByConversationIds〔DISTINCT ON〕 + countHumanMembersByConversationIds) assemble the
 * conversation list correctly, replacing the previous 1+4N loop. @Transactional rolls the seeds back;
 * assertions are scoped to the seeded conversations so they are robust to pre-existing data.
 */
@Transactional
class ImConversationListByUserIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImConversationService conversationService;
    @Autowired
    private ImConversationMapper conversationMapper;
    @Autowired
    private ImConversationMemberMapper memberMapper;
    @Autowired
    private ImMessageMapper messageMapper;

    private Long seedConversation(String name, long maxSeq) {
        ImConversation c = new ImConversation();
        c.setTenantId(testTenant.getId());
        c.setType("GROUP");
        c.setName(name + "-" + System.nanoTime());
        c.setMaxSeq(maxSeq);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        conversationMapper.insert(c);
        return c.getId();
    }

    private void seedMembership(Long convId, long lastReadSeq) {
        ImConversationMember m = new ImConversationMember();
        m.setConversationId(convId);
        m.setTenantId(testTenant.getId());
        m.setMemberType("human");
        m.setMemberId(testUser.getId());
        m.setLastReadSeq(lastReadSeq);
        m.setJoinedAt(Instant.now());
        memberMapper.insert(m);
    }

    private void seedMessage(Long convId, long seq, String content, Instant createdAt) {
        ImMessage m = new ImMessage();
        m.setConversationId(convId);
        m.setTenantId(testTenant.getId());
        m.setSenderId(testUser.getId());
        m.setSenderType("human");
        m.setSeq(seq);
        m.setMessageType("TEXT");
        m.setContent(content);
        m.setCreatedAt(createdAt);
        messageMapper.insert(m);
    }

    @Test
    @DisplayName("listByUser assembles list from batched queries (DISTINCT ON picks the max-seq last message)")
    void listByUser_realDb_batchedAssembly() {
        Long c1 = seedConversation("A", 10L);
        Long c2 = seedConversation("B", 3L);
        seedMembership(c1, 7L);
        seedMembership(c2, 3L);
        seedMessage(c1, 1L, "older", Instant.parse("2026-01-01T00:00:00Z"));
        seedMessage(c1, 2L, "latest", Instant.parse("2026-01-02T00:00:00Z"));
        // c2 has no messages

        List<ConversationListItem> items =
                conversationService.listByUser(testUser.getId(), testTenant.getId());
        Map<Long, ConversationListItem> byId = items.stream()
                .collect(Collectors.toMap(ConversationListItem::getConversationId, i -> i));

        ConversationListItem i1 = byId.get(c1);
        assertThat(i1).isNotNull();
        assertThat(i1.getLastMessage()).isNotNull();
        // DISTINCT ON (conversation_id) ORDER BY seq DESC -> the seq=2 message wins.
        assertThat(i1.getLastMessage().getContent()).isEqualTo("latest");
        assertThat(i1.getUnreadCount()).isEqualTo(3L); // 10 - 7
        assertThat(i1.getMemberCount()).isEqualTo(1);   // only the test user

        ConversationListItem i2 = byId.get(c2);
        assertThat(i2).isNotNull();
        assertThat(i2.getLastMessage()).isNull();       // no messages
        assertThat(i2.getUnreadCount()).isEqualTo(0L);  // 3 - 3
        assertThat(i2.getMemberCount()).isEqualTo(1);
    }
}
