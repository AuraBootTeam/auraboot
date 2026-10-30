package com.auraboot.framework.workbench.service;

import com.auraboot.framework.meta.security.CsvSafetyUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed CSV schema over the captured view, never over mutable recipient records. */
public final class AnnouncementStatisticsCsv implements AutoCloseable {
    public static final String SCHEMA="announcement.statistics.csv.v1";
    public static final List<String> COLUMNS=List.of("title","version","published","recipient","departments","roles",
        "readState","firstRead","ackRequired","ackDeadline","ackState","ackAt","late","membership","notification");
    public static final Set<String> STATES=Set.of("yes","no","none","hidden","unknown","not_applicable","not_yet",
        "unread","read","read_after_migration","not_required","pending_ack","completed","active","inactive",
        "not_generated","queued","failed","canceled");
    private final Writer writer;
    private final Map<String,String> labels;
    private final Map<String,Object> metadata;
    private final ZoneId zone;
    private long bytes;
    private final long maximumBytes;

    public AnnouncementStatisticsCsv(Path output,Map<String,Object> metadata,Map<String,String> labels,long maximumBytes) throws IOException {
        this.metadata=Collections.unmodifiableMap(new LinkedHashMap<>(metadata));this.labels=Map.copyOf(labels);this.maximumBytes=maximumBytes;
        zone=ZoneId.of(required(metadata,"timezone"));
        for(String column:COLUMNS) label("column."+column);
        for(String state:STATES) label("state."+state);
        if(!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS)) throw new IOException("A registered regular export file is required");
        writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);
        try {write("\uFEFF");line(COLUMNS.stream().map(key->label("column."+key)).toList());}
        catch(IOException | RuntimeException failure) {writer.close();throw failure;}
    }

    public void row(Map<String,Object> view,Map<String,Object> organization,Map<String,Object> proof) throws IOException {
        boolean required=bool(metadata,"acknowledgementRequired");
        String read=required(view,"readState"),ack=required(view,"acknowledgementState");
        boolean captured=Boolean.TRUE.equals(organization.get("captured"));
        String departments=names(view,"departments");
        if(departments.isEmpty()) {
            if(!captured) departments=state("unknown");
            else if(list(organization,"departmentPids").isEmpty()) departments=state("none");
            else departments=state("hidden");
        }
        // A hidden department classification must never acquire a label through an export-only path.
        if(!list(proof,"departmentPids").isEmpty() && names(view,"departments").isEmpty())
            throw new IllegalStateException("Statistics department display proof is inconsistent");
        String roles=names(view,"matchedRoles");
        if(roles.isEmpty()) roles=state(captured ? "none" : "unknown");
        line(List.of(required(metadata,"title"),required(metadata,"versionNumber"),time(metadata.get("publishedAt"),"unknown"),
            required(view,"displayName"),departments,roles,state(read),time(view.get("firstReadAt"),"unknown".equals(read) ? "unknown" : "not_yet"),
            state(required ? "yes" : "no"),required ? time(metadata.get("acknowledgementDeadline"),"none") : state("not_applicable"),
            state(ack),required ? time(view.get("acknowledgedAt"),"not_yet") : state("not_applicable"),
            required ? state(bool(view,"lateAcknowledgement") ? "yes" : "no") : state("not_applicable"),
            state(bool(view,"memberEligible") ? "active" : "inactive"),state(required(view,"notificationState"))));
    }
    private String time(Object value,String missing) {
        if(value==null) return state(missing);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.parse(value.toString()).atZone(zone))+" ["+zone.getId()+"]";
    }
    private String state(String value) {
        if(!STATES.contains(value)) throw new IllegalStateException("Unknown statistics CSV state: "+value);
        return label("state."+value);
    }
    private String label(String key) {
        String value=labels.get(key);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing statistics CSV translation: "+key);
        return value;
    }
    private static String required(Map<String,Object> row,String key) {
        Object value=row.get(key);
        if(value==null) throw new IllegalStateException("Missing statistics CSV field: "+key);
        return value.toString();
    }
    private static boolean bool(Map<String,Object> row,String key) {
        if(!(row.get(key) instanceof Boolean value)) throw new IllegalStateException("Invalid statistics CSV boolean: "+key);
        return value;
    }
    private static List<?> list(Map<String,Object> row,String key) {
        if(!(row.get(key) instanceof List<?> value)) throw new IllegalStateException("Invalid statistics CSV list: "+key);
        return value;
    }
    private static String names(Map<String,Object> row,String key) {
        return String.join("; ",list(row,key).stream().map(value->{
            if(!(value instanceof String name)) throw new IllegalStateException("Invalid statistics CSV label");
            return name;
        }).toList());
    }
    private void line(List<String> values) throws IOException {
        write(String.join(",",values.stream().map(CsvSafetyUtils::escapeCsvCell).toList())+"\r\n");
    }
    private void write(String text) throws IOException {
        long next=bytes+text.getBytes(StandardCharsets.UTF_8).length;
        if(next>maximumBytes) throw new IOException("Statistics CSV exceeds the configured artifact limit");
        writer.write(text);bytes=next;
    }
    @Override public void close() throws IOException {writer.close();}
}
