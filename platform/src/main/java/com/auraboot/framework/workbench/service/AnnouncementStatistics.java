package com.auraboot.framework.workbench.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.*;

/** Fixed-time statistics; historical facts and current operational eligibility stay separate. */
public final class AnnouncementStatistics {
    private AnnouncementStatistics() {}
    public static final String PUBLICATION="publication";
    public static final String ALL="all";
    public static final String UNREAD="unread";
    public static final String READ="read";
    public static final String PENDING_ACK="pending_ack";
    public static final String OVERDUE="overdue";
    public static final String COMPLETED="completed";
    public static final String NOT_REQUIRED="not_required";
    public static final String UNKNOWN="unknown";
    public static final String READ_AFTER_MIGRATION="read_after_migration";

    public record Filters(String query,String state,String departmentPid) {
        public Filters {
            if(query==null || query.length()>200 || !Set.of(ALL,UNREAD,PENDING_ACK,OVERDUE).contains(state==null ? "" : state)
                || (departmentPid!=null && (departmentPid.isBlank() || departmentPid.length()>36)))
                throw new IllegalArgumentException("Invalid announcement statistics filters");
        }
        public static Filters all(){return new Filters("",ALL,null);}
    }
    public record Fact(String userPid,String memberPid,String displayName,Map<String,Object> organization,
            Instant firstReadAt,Instant acknowledgedAt,boolean memberEligible,String notificationState) {}
    public record Row(String pid,String displayName,List<String> departments,List<String> matchedRoles,
            String readState,Instant firstReadAt,String acknowledgementState,Instant acknowledgedAt,
            boolean lateAcknowledgement,boolean overdue,boolean memberEligible,boolean actionable,
            String notificationState,@JsonIgnore boolean matchesFilters,
            @JsonIgnore String memberPid,@JsonIgnore Map<String,Object> organization,
            @JsonIgnore List<String> displayedDepartmentPids) {}
    public record Summary(Long actualRecipients,long visibleCohort,Long read,Long acknowledged,Long unread,Long unacknowledged,
            Double readRate,Double acknowledgementRate,
            long readsAfterMigration,long inactiveMembers,Long overdueUnacknowledged,Long lateCompleted,
            long actionable,long filteredRows,String denominatorScope) {}
    public record Capture(List<Row> rows,Summary summary) {}

    public static Capture capture(List<Fact> facts,String origin,boolean acknowledgementRequired,
            Instant acknowledgementDeadline,boolean versionActive,Instant asOf,
            AnnouncementManagementScope scope,Filters filters) {
        boolean historical=PUBLICATION.equals(origin);
        List<Row> rows=new ArrayList<>();long read=0,ack=0,inactive=0,overdue=0,late=0,actionable=0,filtered=0;
        for(Fact fact:facts) {
            if(!scope.contains(fact.userPid(),fact.organization())) continue;
            if(fact.acknowledgedAt()!=null && fact.firstReadAt()==null)
                throw new IllegalStateException("Acknowledgement requires a historical read fact");
            boolean lateAck=acknowledgementRequired && fact.acknowledgedAt()!=null && acknowledgementDeadline!=null
                && fact.acknowledgedAt().isAfter(acknowledgementDeadline);
            boolean pastDue=acknowledgementRequired && fact.acknowledgedAt()==null && acknowledgementDeadline!=null
                && asOf.isAfter(acknowledgementDeadline);
            boolean incomplete=acknowledgementRequired ? fact.acknowledgedAt()==null : fact.firstReadAt()==null;
            boolean canRemind=historical && versionActive && fact.memberEligible() && incomplete;
            String readState=fact.firstReadAt()==null ? historical ? UNREAD : UNKNOWN : historical ? READ : READ_AFTER_MIGRATION;
            String ackState=!acknowledgementRequired ? NOT_REQUIRED : fact.acknowledgedAt()==null ? PENDING_ACK : COMPLETED;
            List<String> departments=new ArrayList<>(),departmentPids=new ArrayList<>();
            if(fact.organization().get("departments") instanceof List<?> values) for(Object value:values) {
                if(!(value instanceof Map<?,?> department) || !(department.get("pid") instanceof String pid)
                    || !(department.get("label") instanceof String label))
                    throw new IllegalStateException("Invalid frozen department classification");
                if(ALL.equals(scope.type()) || "self".equals(scope.type())
                    || (Set.of("dept","dept_and_sub").contains(scope.type()) && scope.unitPids().contains(pid))) {
                    departments.add(label);departmentPids.add(pid);
                }
            }
            List<String> roles=new ArrayList<>();
            if(fact.organization().get("matchedRoles") instanceof List<?> values) for(Object value:values) {
                if(!(value instanceof Map<?,?> role) || !(role.get("label") instanceof String label))
                    throw new IllegalStateException("Invalid frozen matched role classification");
                roles.add(label);
            }
            String name=fact.displayName()==null ? "" : fact.displayName();
            boolean match=name.toLowerCase(Locale.ROOT).contains(filters.query().toLowerCase(Locale.ROOT))
                && (filters.departmentPid()==null || departmentPids.contains(filters.departmentPid()))
                && switch(filters.state()) {
                    case ALL -> true;
                    case UNREAD -> UNREAD.equals(readState);
                    case PENDING_ACK -> PENDING_ACK.equals(ackState);
                    case OVERDUE -> pastDue;
                    default -> throw new IllegalStateException("Unvalidated statistics state");
                };
            rows.add(new Row(fact.userPid(),name,List.copyOf(departments),List.copyOf(roles),readState,fact.firstReadAt(),
                ackState,fact.acknowledgedAt(),lateAck,pastDue,fact.memberEligible(),canRemind,fact.notificationState(),match,
                fact.memberPid(),fact.organization(),List.copyOf(departmentPids)));
            if(fact.firstReadAt()!=null) read++;
            if(acknowledgementRequired && fact.acknowledgedAt()!=null) ack++;
            if(!fact.memberEligible()) inactive++;
            if(pastDue) overdue++;
            if(lateAck) late++;
            if(canRemind) actionable++;
            if(match) filtered++;
        }
        return new Capture(List.copyOf(rows),new Summary(historical ? (long)rows.size() : null,rows.size(),historical ? read : null,
            historical && acknowledgementRequired ? ack : null,historical ? rows.size()-read : null,
            historical && acknowledgementRequired ? rows.size()-ack : null,
            historical && !rows.isEmpty() ? percentage(read,rows.size()) : null,
            historical && acknowledgementRequired && !rows.isEmpty() ? percentage(ack,rows.size()) : null,
            historical ? 0 : read,inactive,
            acknowledgementRequired ? overdue : null,acknowledgementRequired ? late : null,actionable,filtered,scope.type()));
    }
    private static double percentage(long value,long total){return Math.round(10000.0*value/total)/100.0;}
}
