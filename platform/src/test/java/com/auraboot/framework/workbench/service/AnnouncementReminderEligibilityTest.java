package com.auraboot.framework.workbench.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.auraboot.framework.workbench.service.AnnouncementReminderEligibility.*;

class AnnouncementReminderEligibilityTest {
    private static final Instant NOW=Instant.parse("2026-10-25T01:00:00Z");
    private static final Policy POLICY=new Policy(true,true,true,NOW.plusSeconds(1),NOW.plusSeconds(2),Duration.ofHours(1));
    private static Recipient recipient(Instant read,Instant ack,boolean active,Instant intention) {
        return new Recipient("user","membership",read,ack,active,intention);
    }
    @Test void outstandingCoalescesUnreadAndUnacknowledgedIntoOneRecipient() {
        var selected=select(POLICY,Target.OUTSTANDING,List.of(recipient(null,null,true,null)),NOW,false);
        assertThat(selected.eligible()).isEqualTo(1);
        assertThat(selected.recipients()).extracting(Recipient::userPid).containsExactly("user");
    }
    @Test void readingDoesNotCompleteAcknowledgement() {
        var read=recipient(NOW.minusSeconds(1),null,true,null);
        assertThat(evaluate(POLICY,Target.UNREAD,read,NOW,false,true)).isEqualTo(Reason.ALREADY_COMPLETE);
        assertThat(evaluate(POLICY,Target.UNACKNOWLEDGED,read,NOW,false,true)).isEqualTo(Reason.ELIGIBLE);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,read,NOW,false,true)).isEqualTo(Reason.ELIGIBLE);
    }
    @Test void noAcknowledgementPolicyDoesNotInventPendingWork() {
        var policy=new Policy(true,true,false,null,null,Duration.ofHours(1));
        assertThat(evaluate(policy,Target.UNACKNOWLEDGED,recipient(null,null,true,null),NOW,false,true)).isEqualTo(Reason.ALREADY_COMPLETE);
        assertThat(evaluate(policy,Target.OUTSTANDING,recipient(NOW,null,true,null),NOW,false,true)).isEqualTo(Reason.ALREADY_COMPLETE);
    }
    @Test void bothModesShareIntentionWindowAndBoundaryIsInclusive() {
        var pending=recipient(null,null,true,NOW.minusSeconds(3599));
        for(boolean automatic:List.of(false,true))
            assertThat(evaluate(POLICY,Target.OUTSTANDING,pending,NOW,automatic,true)).isEqualTo(Reason.THROTTLED);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,recipient(null,null,true,NOW.minusSeconds(3600)),NOW,false,true)).isEqualTo(Reason.ELIGIBLE);
    }
    @Test void completionBeforeConsumptionSkipsAcceptedIntention() {
        var accepted=recipient(null,null,true,NOW);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,accepted,NOW,false,false)).isEqualTo(Reason.ELIGIBLE);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,recipient(NOW,NOW,true,NOW),NOW,false,false)).isEqualTo(Reason.ALREADY_COMPLETE);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,recipient(null,null,false,NOW),NOW,false,false)).isEqualTo(Reason.MEMBERSHIP_INACTIVE);
    }
    @Test void deadlineStopsAutomaticButAllowsManualUntilExpiry() {
        var pending=recipient(null,null,true,null);var due=NOW.plusSeconds(1);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,pending,due,true,true)).isEqualTo(Reason.AUTOMATIC_DEADLINE);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,pending,due,false,true)).isEqualTo(Reason.ELIGIBLE);
        assertThat(evaluate(POLICY,Target.OUTSTANDING,pending,NOW.plusSeconds(2),false,true)).isEqualTo(Reason.VERSION_INACTIVE);
    }
    @Test void legacyAndWithdrawnVersionsNeverBecomeUnreadCandidates() {
        var pending=recipient(null,null,true,null);
        assertThat(evaluate(new Policy(false,true,true,null,null,Duration.ofHours(1)),Target.OUTSTANDING,pending,NOW,false,true)).isEqualTo(Reason.LEGACY_UNKNOWN);
        assertThat(evaluate(new Policy(true,false,true,null,null,Duration.ofHours(1)),Target.OUTSTANDING,pending,NOW,false,true)).isEqualTo(Reason.VERSION_INACTIVE);
    }
    @Test void throttledAndInactiveCandidatesDoNotConsumePositiveBatchCount() {
        var inactive=new Recipient("inactive","membership2",null,null,false,null);
        var selected=select(POLICY,Target.OUTSTANDING,List.of(recipient(null,null,true,NOW),inactive),NOW,true);
        assertThat(selected.eligible()).isZero();assertThat(selected.throttled()).isEqualTo(1);assertThat(selected.recipients()).isEmpty();
    }
    @Test void invalidOrDuplicateIdentityFailsInsteadOfInflatingQuota() {
        var pending=recipient(null,null,true,null);
        assertThatThrownBy(()->select(POLICY,Target.OUTSTANDING,List.of(pending,pending),NOW,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Recipient("user","membership",null,NOW,true,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Policy(true,true,true,null,null,Duration.ofMinutes(59))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Policy(true,true,true,null,null,Duration.ofDays(2))).isInstanceOf(IllegalArgumentException.class);
    }
}
