package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailAccountMemberMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailAccountMember;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.SharedMailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link SharedMailboxService} — no Redis, in-process counter only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedMailboxService Unit Tests")
class SharedMailboxServiceUnitTest {

    @Mock
    private EmailAccountMemberMapper memberMapper;
    @Mock
    private EmailMessageMapper messageMapper;

    private SharedMailboxService service;

    @BeforeEach
    void setUp() {
        // StringRedisTemplate is null → fallback to LOCAL_COUNTERS
        service = new SharedMailboxService(memberMapper, messageMapper);
    }

    private static EmailAccount account(long id, String type) {
        EmailAccount a = new EmailAccount();
        a.setId(id);
        a.setAccountType(type);
        return a;
    }

    private static EmailMessage message(long id) {
        EmailMessage m = new EmailMessage();
        m.setId(id);
        return m;
    }

    private static EmailAccountMember member(long userId, Integer weight) {
        EmailAccountMember m = new EmailAccountMember();
        m.setUserId(userId);
        m.setAssignmentWeight(weight);
        return m;
    }

    @Test
    @DisplayName("assign returns null for personal account")
    void assign_personalAccount_null() {
        EmailAccount acc = account(1L, EmailConstants.ACCOUNT_TYPE_PERSONAL);
        assertThat(service.assign(acc, message(10L), "round_robin")).isNull();
        verify(memberMapper, never()).findByAccountId(any());
    }

    @Test
    @DisplayName("assign returns null for manual strategy")
    void assign_manualStrategy_null() {
        EmailAccount acc = account(2L, EmailConstants.ACCOUNT_TYPE_SHARED);
        assertThat(service.assign(acc, message(11L), "manual")).isNull();
        assertThat(service.assign(acc, message(11L), "MANUAL")).isNull();
    }

    @Test
    @DisplayName("assign returns null when shared account has no members")
    void assign_noMembers_null() {
        EmailAccount acc = account(3L, EmailConstants.ACCOUNT_TYPE_SHARED);
        when(memberMapper.findByAccountId(3L)).thenReturn(List.of());
        assertThat(service.assign(acc, message(12L), "round_robin")).isNull();
    }

    @Test
    @DisplayName("assign returns null when memberMapper returns null")
    void assign_nullMemberList_null() {
        EmailAccount acc = account(31L, EmailConstants.ACCOUNT_TYPE_SHARED);
        when(memberMapper.findByAccountId(31L)).thenReturn(null);
        assertThat(service.assign(acc, message(13L), "round_robin")).isNull();
    }

    @Test
    @DisplayName("assign uses weighted slots and persists assigned user via updateById")
    void assign_weightedRoundRobin() {
        // accountId=4 unique to ensure clean local counter
        EmailAccount acc = account(40L, EmailConstants.ACCOUNT_TYPE_SHARED);
        when(memberMapper.findByAccountId(40L))
                .thenReturn(List.of(member(100L, 2), member(200L, 1)));

        EmailMessage msg = message(20L);
        Long assigned = service.assign(acc, msg, "round_robin");
        // Slots = [100, 100, 200] — first call (counter=1) → slot 0 → user 100
        assertThat(assigned).isEqualTo(100L);
        assertThat(msg.getAssignedTo()).isEqualTo(100L);
        verify(messageMapper).updateById(any(EmailMessage.class));
    }

    @Test
    @DisplayName("assign cycles through weighted slots across calls")
    void assign_cycles() {
        EmailAccount acc = account(41L, EmailConstants.ACCOUNT_TYPE_SHARED);
        when(memberMapper.findByAccountId(41L))
                .thenReturn(List.of(member(1L, 1), member(2L, 1), member(3L, 1)));

        Long a1 = service.assign(acc, message(1L), "round_robin");
        Long a2 = service.assign(acc, message(2L), "round_robin");
        Long a3 = service.assign(acc, message(3L), "round_robin");
        Long a4 = service.assign(acc, message(4L), "round_robin");

        assertThat(a1).isEqualTo(1L);
        assertThat(a2).isEqualTo(2L);
        assertThat(a3).isEqualTo(3L);
        assertThat(a4).isEqualTo(1L); // wraps
    }

    @Test
    @DisplayName("assign treats null/zero/negative weight as 1 slot per member")
    void assign_defaultWeightForInvalid() {
        EmailAccount acc = account(42L, EmailConstants.ACCOUNT_TYPE_SHARED);
        when(memberMapper.findByAccountId(42L))
                .thenReturn(List.of(member(7L, null), member(8L, 0), member(9L, -5)));

        Long a = service.assign(acc, message(50L), null);
        // null strategy falls through to round_robin
        assertThat(a).isIn(7L, 8L, 9L);
        verify(messageMapper).updateById(any(EmailMessage.class));
    }

    @Test
    @DisplayName("claim updates message assignedTo via mapper")
    void claim_updates() {
        service.claim(123L, 456L);
        verify(messageMapper).updateById(any(EmailMessage.class));
    }
}
