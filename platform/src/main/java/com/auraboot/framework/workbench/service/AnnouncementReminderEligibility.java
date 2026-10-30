package com.auraboot.framework.workbench.service;

import java.time.*;
import java.util.*;

/** Shared acceptance/consumption rules; a persisted intention, rather than delivery success, starts deduplication. */
public final class AnnouncementReminderEligibility {
    private AnnouncementReminderEligibility() {}

    public enum Target { UNREAD, UNACKNOWLEDGED, OUTSTANDING }
    public enum Reason { ELIGIBLE, LEGACY_UNKNOWN, VERSION_INACTIVE, MEMBERSHIP_INACTIVE, ALREADY_COMPLETE, THROTTLED, AUTOMATIC_DEADLINE }
    public record Recipient(String userPid,String memberPid,Instant readAt,Instant acknowledgedAt,boolean membershipActive,Instant lastIntentionAt) {
        public Recipient {
            if(userPid==null || userPid.isBlank() || memberPid==null || memberPid.isBlank())
                throw new IllegalArgumentException("Reminder requires fixed recipient and membership identities");
            if(acknowledgedAt!=null && readAt==null)
                throw new IllegalArgumentException("Acknowledgement requires a read fact");
        }
    }
    public record Policy(boolean publicationOrigin,boolean versionActive,boolean acknowledgementRequired,
            Instant acknowledgementDeadline,Instant expiresAt,Duration dedupe) {
        public Policy {
            if(dedupe==null || dedupe.compareTo(Duration.ofHours(1))<0 || dedupe.compareTo(Duration.ofDays(1))>0)
                throw new IllegalArgumentException("Reminder deduplication must be between one hour and one day");
        }
    }
    public record Decision(Recipient recipient,Reason reason) {}
    public record Selection(List<Decision> decisions,long eligible,long throttled) {
        public Selection {decisions=List.copyOf(decisions);}
        public List<Recipient> recipients() {
            return decisions.stream().filter(row->row.reason()==Reason.ELIGIBLE).map(Decision::recipient).toList();
        }
    }

    public static Reason evaluate(Policy policy,Target target,Recipient recipient,Instant now,boolean automatic,boolean accepting) {
        Objects.requireNonNull(policy);Objects.requireNonNull(target);Objects.requireNonNull(recipient);Objects.requireNonNull(now);
        if(!policy.publicationOrigin()) return Reason.LEGACY_UNKNOWN;
        if(!policy.versionActive() || atOrAfter(now,policy.expiresAt())) return Reason.VERSION_INACTIVE;
        if(!recipient.membershipActive()) return Reason.MEMBERSHIP_INACTIVE;
        if(automatic && atOrAfter(now,policy.acknowledgementDeadline())) return Reason.AUTOMATIC_DEADLINE;
        boolean unread=recipient.readAt()==null;
        boolean unacknowledged=policy.acknowledgementRequired() && recipient.acknowledgedAt()==null;
        boolean incomplete=switch(target) {
            case UNREAD -> unread;
            case UNACKNOWLEDGED -> unacknowledged;
            case OUTSTANDING -> unread || unacknowledged;
        };
        if(!incomplete) return Reason.ALREADY_COMPLETE;
        // Consumption rechecks completion/membership but must not throttle its own accepted intention.
        if(accepting && recipient.lastIntentionAt()!=null && now.isBefore(recipient.lastIntentionAt().plus(policy.dedupe())))
            return Reason.THROTTLED;
        return Reason.ELIGIBLE;
    }

    public static Selection select(Policy policy,Target target,List<Recipient> recipients,Instant now,boolean automatic) {
        Set<String> users=new HashSet<>();List<Decision> decisions=new ArrayList<>();long eligible=0,throttled=0;
        for(var recipient:recipients) {
            if(!users.add(recipient.userPid())) throw new IllegalArgumentException("Reminder selection contains duplicate recipients");
            var reason=evaluate(policy,target,recipient,now,automatic,true);
            if(reason==Reason.ELIGIBLE) eligible++;
            if(reason==Reason.THROTTLED) throttled++;
            decisions.add(new Decision(recipient,reason));
        }
        return new Selection(decisions,eligible,throttled);
    }
    private static boolean atOrAfter(Instant now,Instant limit) {return limit!=null && !now.isBefore(limit);}
}
