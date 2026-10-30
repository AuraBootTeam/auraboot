package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.workbench.mapper.AnnouncementStatisticsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Announcement adapter for the shared durable export host. No source identity comes from queue input. */
@Component
@RequiredArgsConstructor
public class AnnouncementStatisticsExportSource implements DomainExportSource {
    public static final String CODE="announcement.statistics.v1";
    private final AnnouncementStatisticsService statistics;
    private final AnnouncementStatisticsMapper rows;
    private final AnnouncementActorContext actors;
    private final AnnouncementCodec codec;
    private final ObjectMapper json;
    private final I18nService messages;
    @Value("${auraboot.announcement.statistics-export-max-bytes:52428800}") private long maximumBytes;

    @Override public String code(){return CODE;}

    @Override public Prepared prepare(String reference) {
        var snapshot=statistics.authorizeExport(reference,true);
        String locale=LocaleContextHolder.getLocale().toLanguageTag();
        Map<String,String> labels=new TreeMap<>();
        AnnouncementStatisticsCsv.COLUMNS.forEach(key->labels.put("column."+key,translation(locale,"column."+key)));
        AnnouncementStatisticsCsv.STATES.forEach(key->labels.put("state."+key,translation(locale,"state."+key)));
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("schema",AnnouncementStatisticsCsv.SCHEMA);evidence.put("snapshot",facts(snapshot));evidence.put("labels",labels);
        evidence.put("digest",codec.hash(evidence));
        return new Prepared(reference,(String)snapshot.get("owner_member_pid"),locale,
            "announcement-statistics.csv","csv",((Number)snapshot.get("filtered_count")).longValue(),json.valueToTree(evidence));
    }

    @Override public void authorize(Prepared prepared) {
        var snapshot=statistics.authorizeExport(prepared.reference(),false);
        verify(prepared,snapshot);
    }

    @Override public void authorizeForOwner(Prepared prepared,Owner owner) {
        if(!prepared.memberPid().equals(owner.memberPid())) throw new AccessDeniedException("Export membership binding changed");
        actors.call(owner.tenantId(),owner.userId(),owner.memberPid(),()->{authorize(prepared);return null;});
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public boolean visible(Prepared prepared) {
        try {authorize(prepared);return true;}
        // Only an explicit permission denial hides a task; persistence and schema failures remain observable.
        catch(AccessDeniedException | AnnouncementStatisticsService.Unavailable | AnnouncementAccessService.RecordUnavailable denied) {return false;}
    }

    @Override public com.fasterxml.jackson.databind.JsonNode auditMetadata(Prepared prepared) {
        var captured=prepared.evidence().required("snapshot");
        return json.createObjectNode().put("versionPid",captured.required("versionPid").textValue())
            .put("asOf",captured.required("asOf").textValue()).put("rowCount",prepared.rowCount())
            .set("filters",captured.required("filters"));
    }

    @Override public com.fasterxml.jackson.databind.JsonNode publicMetadata(Prepared prepared) {
        var captured=prepared.evidence().required("snapshot");var metadata=captured.required("metadata");
        var view=json.createObjectNode().put("recordPid",metadata.required("recordPid").textValue())
            .put("title",metadata.required("title").textValue()).put("versionNumber",metadata.required("versionNumber").intValue())
            .put("snapshotOrigin",metadata.required("snapshotOrigin").textValue());
        view.setAll((com.fasterxml.jackson.databind.node.ObjectNode)auditMetadata(prepared));return view;
    }

    @Override public ExportResult generate(Prepared prepared,Owner owner,Path output,AsyncTaskExecutor.ProgressCallback progress) {
        if(!prepared.memberPid().equals(owner.memberPid())) throw new AccessDeniedException("Export membership binding changed");
        return actors.call(owner.tenantId(),owner.userId(),owner.memberPid(),()->{
            var snapshot=statistics.authorizeExport(prepared.reference(),false);verify(prepared,snapshot);
            var metadata=codec.object(snapshot.get("metadata").toString());
            Map<String,String> labels=new LinkedHashMap<>();
            prepared.evidence().required("labels").properties().forEach(entry->{
                if(!entry.getValue().isTextual()) throw new IllegalStateException("Invalid frozen CSV translation");
                labels.put(entry.getKey(),entry.getValue().textValue());
            });
            long count=0;
            try(var csv=new AnnouncementStatisticsCsv(output,metadata,labels,maximumBytes)) {
                while(count<prepared.rowCount()) {
                    if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Export execution interrupted");
                    var page=rows.exportPage(MetaContext.getCurrentTenantId(),prepared.reference(),500,Math.toIntExact(count));
                    if(page.isEmpty() || count+page.size()>prepared.rowCount()) throw new IllegalStateException("Captured CSV row count changed");
                    for(var row:page) csv.row(codec.object(row.get("view_payload").toString()),
                        codec.object(row.get("organization_snapshot").toString()),codec.object(row.get("display_proof").toString()));
                    count+=page.size();progress.report((int)(count*90/Math.max(1,prepared.rowCount())),null);
                }
            } catch(IOException failure) {throw new UncheckedIOException("Unable to generate the private announcement CSV",failure);}
            try {
                return ExportResult.builder().success(true).filePath(output.toString()).recordCount(count).fileSize(Files.size(output))
                    .format("csv").exportTime(Instant.now()).rowSetDigest(prepared.evidence().required("digest").textValue()).build();
            } catch(IOException failure) {throw new UncheckedIOException("Unable to inspect the private announcement CSV",failure);}
        });
    }

    private void verify(Prepared prepared,Map<String,Object> snapshot) {
        var evidence=codec.object(prepared.evidence().toString());Object digest=evidence.remove("digest");
        if(!Objects.equals(digest,codec.hash(evidence)) || !AnnouncementStatisticsCsv.SCHEMA.equals(evidence.get("schema"))
            || !Objects.equals(prepared.memberPid(),snapshot.get("owner_member_pid"))
            || prepared.rowCount()!=((Number)snapshot.get("filtered_count")).longValue()
            || !Objects.equals(codec.hash(evidence.get("snapshot")),codec.hash(facts(snapshot))))
            throw new IllegalStateException("Announcement export evidence binding changed");
    }
    private Map<String,Object> facts(Map<String,Object> snapshot) {
        return Map.of("asOf",AnnouncementDeliveryPlanResolver.instant(snapshot.get("as_of")).toString(),
            "versionPid",snapshot.get("version_pid"),"schema",snapshot.get("snapshot_schema"),"rowCount",snapshot.get("filtered_count"),
            "metadata",codec.object(snapshot.get("metadata").toString()),"filters",codec.object(snapshot.get("filters").toString()),
            "summary",codec.object(snapshot.get("summary").toString()));
    }
    private String translation(String locale,String key) {
        String value=messages.getValue(locale,"announcement.statistics.csv."+key);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing announcement export translation: "+locale+":"+key);
        return value;
    }
}
