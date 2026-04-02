package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for shared mailbox assignment and claim operations.
 *
 * <p>Supports two assignment strategies:
 * <ul>
 *   <li><b>round_robin</b> — weighted round-robin using a Redis counter per account.
 *       Members are ordered by {@code assignment_weight} descending, and the Redis
 *       counter selects the next assignee cyclically proportional to their weight.</li>
 *   <li><b>manual</b> — no automatic assignment; returns {@code null}.</li>
 * </ul>
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharedMailboxService {

    /** Redis key prefix for round-robin counter: {@code email:rr:account:{accountId}}. */
    private static final String RR_KEY_PREFIX = "email:rr:account:";
    private static final ConcurrentHashMap<Long, AtomicLong> LOCAL_COUNTERS = new ConcurrentHashMap<>();

    private final EmailAccountMemberMapper emailAccountMemberMapper;
    private final EmailMessageMapper       emailMessageMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    // ──────────────────────────────────────────────────────────────────────────
    // Assign
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Assigns an incoming message to a mailbox member according to the given strategy.
     *
     * <p>If the account is not a shared mailbox, or strategy is {@code manual},
     * {@code null} is returned and the message remains unassigned.
     *
     * <p>The {@code round_robin} strategy expands each member into N "slots" equal
     * to their {@code assignment_weight} (defaulting to 1), then uses a Redis
     * INCR counter to pick the next slot and resolve the corresponding member.
     *
     * @param account  the shared mailbox account
     * @param message  the inbound message to assign
     * @param strategy {@code round_robin} (default) or {@code manual}
     * @return the assigned user ID, or {@code null} if not assigned
     */
    public Long assign(EmailAccount account, EmailMessage message, String strategy) {
        // Only shared accounts support auto-assignment
        if (!EmailConstants.ACCOUNT_TYPE_SHARED.equals(account.getAccountType())) {
            return null;
        }

        // Manual strategy — no auto-assignment
        if ("manual".equalsIgnoreCase(strategy)) {
            return null;
        }

        // Default: round_robin
        List<EmailAccountMember> members = emailAccountMemberMapper.findByAccountId(account.getId());
        if (members == null || members.isEmpty()) {
            log.warn("assign: no members for shared accountId={}, message unassigned", account.getId());
            return null;
        }

        // Build a weighted slot list: member with weight=3 gets 3 slots
        List<Long> slots = buildWeightedSlots(members);
        if (slots.isEmpty()) {
            return null;
        }

        // Atomically increment the counter and pick a slot
        String redisKey = RR_KEY_PREFIX + account.getId();
        Long counter;
        if (stringRedisTemplate != null) {
            counter = stringRedisTemplate.opsForValue().increment(redisKey);
            if (counter == null) counter = 0L;
        } else {
            // Fallback without Redis: keep deterministic in-process round-robin semantics.
            counter = LOCAL_COUNTERS
                    .computeIfAbsent(account.getId(), ignored -> new AtomicLong(0))
                    .incrementAndGet();
        }

        int slotIndex    = (int) (Math.abs(counter - 1) % slots.size());
        Long assignedTo  = slots.get(slotIndex);

        // Persist the assignment on the message
        EmailMessage update = new EmailMessage();
        update.setId(message.getId());
        update.setAssignedTo(assignedTo);
        emailMessageMapper.updateById(update);

        message.setAssignedTo(assignedTo);
        log.info("assign: messageId={} assigned to userId={} via round_robin (slot={}/{})",
                message.getId(), assignedTo, slotIndex, slots.size());

        return assignedTo;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Claim
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Manually claims an email message for the given user.
     *
     * <p>Any existing assignment is overwritten.
     *
     * @param messageId the message to claim
     * @param userId    the user claiming the message
     */
    public void claim(Long messageId, Long userId) {
        EmailMessage update = new EmailMessage();
        update.setId(messageId);
        update.setAssignedTo(userId);
        emailMessageMapper.updateById(update);
        log.info("claim: messageId={} claimed by userId={}", messageId, userId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Expands the member list into a weighted slot list.
     * Members with weight <= 0 or null get a single slot.
     */
    private List<Long> buildWeightedSlots(List<EmailAccountMember> members) {
        java.util.List<Long> slots = new java.util.ArrayList<>();
        for (EmailAccountMember member : members) {
            int weight = (member.getAssignmentWeight() != null && member.getAssignmentWeight() > 0)
                    ? member.getAssignmentWeight()
                    : 1;
            for (int i = 0; i < weight; i++) {
                slots.add(member.getUserId());
            }
        }
        return slots;
    }
}
