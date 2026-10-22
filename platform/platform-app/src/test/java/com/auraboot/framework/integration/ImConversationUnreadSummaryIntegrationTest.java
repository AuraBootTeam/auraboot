package com.auraboot.framework.integration;

import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.service.ImConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression for the batched {@code getUnreadSummary} (deep-review DR-20260701 W2-perf-003):
 * verifies the single member ⋈ conversation join (findUnreadRowsByMember) returns correct
 * per-conversation unread counts, replacing the previous N+1 loop of per-conversation
 * selectById + findMember. Runs against the integration-test Postgres; @Transactional rolls the
 * seeded rows back and the per-conversation assertions are robust to any pre-existing data.
 */
@Transactional
class ImConversationUnreadSummaryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImConversationService conversationService;
    @Autowired
    private ImConversationMapper conversationMapper;
    @Autowired
    private ImConversationMemberMapper memberMapper;

    private Long seedConversation(long maxSeq) {
        ImConversation c = new ImConversation();
        c.setTenantId(testTenant.getId());
        c.setType("GROUP");
        c.setName("unread-it-" + System.nanoTime());
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

    @Test
    @DisplayName("getUnreadSummary computes unread via a single join across the member's conversations")
    void getUnreadSummary_realDb_joinComputesUnread() {
        Long c1 = seedConversation(10L); // lastRead 7 -> 3 unread
        Long c2 = seedConversation(5L);  // lastRead 5 -> 0 unread (excluded)
        Long c3 = seedConversation(8L);  // lastRead 2 -> 6 unread
        seedMembership(c1, 7L);
        seedMembership(c2, 5L);
        seedMembership(c3, 2L);

        UnreadSummary summary = conversationService.getUnreadSummary(testUser.getId(), testTenant.getId());

        // Robust to any pre-existing conversations for the test user: assert on our seeded convs only.
        Map<Long, Long> byConv = summary.getConversations().stream()
                .collect(Collectors.toMap(
                        UnreadSummary.ConversationUnread::getConversationId,
                        UnreadSummary.ConversationUnread::getUnread));
        assertThat(byConv).containsEntry(c1, 3L);
        assertThat(byConv).containsEntry(c3, 6L);
        assertThat(byConv).doesNotContainKey(c2); // zero-unread conversation excluded
    }
}
