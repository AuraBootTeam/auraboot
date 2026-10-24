package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.workbench.mapper.AnnouncementStatisticsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** A bounded immutable capture supplies the summary, paged details and later export from one as-of time. */
@Service
@RequiredArgsConstructor
public class AnnouncementStatisticsService {
    private final AnnouncementAccessService access;
    private final AnnouncementAudienceService audiences;
    private final AnnouncementStatisticsMapper statistics;
    private final AnnouncementCodec codec;
    @Value("${auraboot.announcement.statistics-max-recipients:50000}") private int maximumRecipients;

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> capture(String version,AnnouncementStatistics.Filters filters) {
        access.actor("stats");
        var scope=audiences.managementScope("stats");
        Long tenant=MetaContext.getCurrentTenantId();
        var source=statistics.version(tenant,version);
        if(source==null) invalid("statistics_unavailable");
        access.manage((String)source.get("announcement_pid"),"stats",false);
        long expected=((Number)source.get("recipient_count")).longValue();
        if(maximumRecipients<1 || expected>maximumRecipients) invalid("statistics_capacity_exceeded");
        Instant asOf=statistics.databaseNow();
        var snapshot=codec.object(source.get("snapshot").toString());
        boolean required=snapshot.get("policy") instanceof Map<?,?> policy && Boolean.TRUE.equals(policy.get("acknowledgementRequired"));
        String origin=(String)source.get("origin");
        String timezone=AnnouncementStatistics.PUBLICATION.equals(origin) ? (String)snapshot.get("timezone") : "UTC";
        if(timezone==null) throw new IllegalStateException("Published statistics have no frozen timezone");
        ZoneId.of(timezone);
        Instant expiry=time(source.get("expires_at"));
        boolean active=Boolean.TRUE.equals(source.get("version_active")) && (expiry==null || asOf.isBefore(expiry));
        var raw=statistics.facts(tenant,version);
        if(raw.size()!=expected) throw new IllegalStateException("Sealed announcement audience count changed");
        var facts=raw.stream().map(row->new AnnouncementStatistics.Fact((String)row.get("user_pid"),(String)row.get("member_pid"),
            (String)row.get("display_name_snapshot"),codec.object(row.get("organization_snapshot").toString()),time(row.get("first_read_at")),
            time(row.get("acknowledged_at")),Boolean.TRUE.equals(row.get("member_eligible")),(String)row.get("notification_state"))).toList();
        var result=AnnouncementStatistics.capture(facts,origin,required,time(source.get("ack_due_at")),active,asOf,scope,filters);
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("recordPid",source.get("announcement_pid"));metadata.put("versionPid",version);metadata.put("versionNumber",source.get("version_no"));
        metadata.put("title",snapshot.get("title"));metadata.put("snapshotOrigin",origin);metadata.put("publishedAt",iso(source.get("published_at")));
        metadata.put("acknowledgementRequired",required);metadata.put("acknowledgementDeadline",iso(source.get("ack_due_at")));
        metadata.put("timezone",timezone);metadata.put("timezoneOrigin",AnnouncementStatistics.PUBLICATION.equals(origin) ? "publication" : "migration_unknown");
        String pid=UniqueIdGenerator.generate(),member=currentMember();
        if(statistics.insertSnapshot(tenant,pid,version,MetaContext.getCurrentUserId(),member,asOf,codec.encode(metadata),codec.encode(result.summary()),
            codec.encode(filters),result.rows().size(),result.summary().filteredRows())!=1)
            throw new IllegalStateException("Announcement statistics header was not persisted");
        for(int start=0;start<result.rows().size();start+=500) {
            var rows=result.rows().subList(start,Math.min(start+500,result.rows().size())).stream()
                .map(row->new AnnouncementStatisticsMapper.StoredRow(row.pid(),row.memberPid(),codec.encode(row.organization()),
                    codec.encode(Map.of("departmentPids",row.displayedDepartmentPids())),codec.encode(row),row.matchesFilters())).toList();
            if(statistics.insertRows(tenant,pid,rows)!=rows.size()) throw new IllegalStateException("Announcement statistics rows were not persisted");
        }
        if(statistics.seal(tenant,pid)!=1) throw new IllegalStateException("Announcement statistics snapshot was not sealed");
        return publicSnapshot(pid,statistics.snapshot(tenant,pid));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> summary(String token) {return publicSnapshot(token,authorize(token,"stats",true));}

    @Transactional(readOnly=true)
    public Map<String,Object> page(String token,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>100) invalid("invalid_input");
        var snapshot=authorize(token,"stats",true);
        return Map.of("records",statistics.page(MetaContext.getCurrentTenantId(),token,size,(page-1)*size).stream().map(codec::object).toList(),
            "total",snapshot.get("filtered_count"),"pageNum",page,"pageSize",size,"asOf",iso(snapshot.get("as_of")),
            "versionPid",snapshot.get("version_pid"),"filters",codec.object(snapshot.get("filters").toString()));
    }

    /** Internal source adapter entry: view-token expiry cannot invalidate an accepted 24-hour export. */
    @Transactional(readOnly=true)
    public Map<String,Object> authorizeExport(String token,boolean accepting) {return authorize(token,"export",accepting);}

    private Map<String,Object> authorize(String token,String action,boolean requireViewLifetime) {
        access.actor("stats");
        var scope=audiences.managementScope(action);
        Long tenant=MetaContext.getCurrentTenantId();
        var snapshot=statistics.snapshot(tenant,token);
        if(snapshot==null) invalid("statistics_unavailable");
        if(!Objects.equals(((Number)snapshot.get("owner_user_id")).longValue(),MetaContext.getCurrentUserId())
            || !Objects.equals(snapshot.get("owner_member_pid"),currentMember())) denied();
        Instant now=Instant.now();
        if(!now.isBefore(time(snapshot.get(requireViewLifetime ? "view_expires_at" : "retained_until")))) invalid("statistics_expired");
        var metadata=codec.object(snapshot.get("metadata").toString());
        access.manage((String)metadata.get("recordPid"),"stats",false);
        if(!"stats".equals(action)) access.manage((String)metadata.get("recordPid"),action,false);
        var rows=statistics.authorizationRows(tenant,token);
        if(rows.size()!=((Number)snapshot.get("row_count")).longValue()) throw new IllegalStateException("Statistics authorization rows changed");
        for(var row:rows) {
            if(!scope.contains((String)row.get("user_pid"),codec.object(row.get("organization_snapshot").toString()))) denied();
            var proof=codec.object(row.get("display_proof").toString());
            if(!(proof.get("departmentPids") instanceof List<?> displayed)) throw new IllegalStateException("Statistics display proof is invalid");
            if(!AnnouncementStatistics.ALL.equals(scope.type()) && !"self".equals(scope.type()) && !scope.unitPids().containsAll(displayed)) denied();
        }
        return snapshot;
    }

    private Map<String,Object> publicSnapshot(String token,Map<String,Object> snapshot) {
        return Map.of("snapshotToken",token,"schema",snapshot.get("snapshot_schema"),"asOf",iso(snapshot.get("as_of")),
            "expiresAt",iso(snapshot.get("view_expires_at")),"versionPid",snapshot.get("version_pid"),
            "metadata",codec.object(snapshot.get("metadata").toString()),"summary",codec.object(snapshot.get("summary").toString()),
            "filters",codec.object(snapshot.get("filters").toString()));
    }
    private String currentMember() {
        String member=statistics.currentMember(MetaContext.getCurrentTenantId(),MetaContext.getCurrentMemberId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentUserPid());
        if(member==null) denied();return member;
    }
    @Scheduled(fixedDelayString="${auraboot.announcement.statistics-cleanup-ms:3600000}")
    public void deleteExpired() {MetaContext.runWithoutTenantFilter(statistics::deleteExpired);}
    private static Instant time(Object value){return value==null ? null : AnnouncementDeliveryPlanResolver.instant(value);}
    private static String iso(Object value){var instant=time(value);return instant==null ? null : instant.toString();}
    public static final class Unavailable extends BusinessException {
        public enum Reason { EXPIRED, MISSING }
        private final Reason reason;
        public Unavailable(Reason reason) {
            super("$i18n:announcement.error."+(reason==Reason.EXPIRED ? "statistics_expired" : "statistics_unavailable"));this.reason=reason;
        }
        public Reason reason(){return reason;}
    }
    private static void invalid(String reason){
        if("statistics_expired".equals(reason)) throw new Unavailable(Unavailable.Reason.EXPIRED);
        if("statistics_unavailable".equals(reason)) throw new Unavailable(Unavailable.Reason.MISSING);
        throw BusinessException.i18n("announcement.error."+reason);
    }
    private static void denied(){throw new AccessDeniedException("Announcement statistics permissions changed; create a new snapshot");}
}
