package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.ExportResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.nio.file.Path;

/** Host business adapters supply immutable data and current authorization to the shared export lifecycle. */
public interface DomainExportSource {
    String code();
    record Owner(Long tenantId,Long userId,String memberPid) {}
    record Prepared(String reference,String memberPid,String locale,String fileName,String format,long rowCount,JsonNode evidence) {
        /** Retain the precise JSONB evidence tree; bean conversion may normalize decimal scale. */
        public static Prepared fromJson(JsonNode value) {
            if(value==null || !value.isObject() || !value.path("rowCount").isIntegralNumber() || !value.path("rowCount").canConvertToLong())
                throw new IllegalArgumentException("Invalid persisted domain export descriptor");
            return new Prepared(text(value,"reference"),text(value,"memberPid"),text(value,"locale"),text(value,"fileName"),
                text(value,"format"),value.required("rowCount").longValue(),value.required("evidence"));
        }
        private static String text(JsonNode value,String field) {
            if(!value.path(field).isTextual()) throw new IllegalArgumentException("Invalid persisted domain export descriptor field: "+field);
            return value.required(field).textValue();
        }
        public Prepared {
            Objects.requireNonNull(reference);Objects.requireNonNull(memberPid);Objects.requireNonNull(locale);
            Objects.requireNonNull(fileName);Objects.requireNonNull(format);Objects.requireNonNull(evidence);
            if(reference.isBlank() || reference.length()>100 || memberPid.isBlank() || memberPid.length()>36
                || locale.length()>35 || fileName.length()>160 || fileName.contains("/") || fileName.contains("\\")
                || fileName.chars().anyMatch(Character::isISOControl) || !"csv".equals(format)
                || !fileName.endsWith(".csv") || rowCount<0 || !evidence.isObject())
                throw new IllegalArgumentException("Invalid prepared domain export descriptor");
        }
    }
    Prepared prepare(String reference);
    void authorize(Prepared prepared);
    void authorizeForOwner(Prepared prepared,Owner owner);
    boolean visible(Prepared prepared);
    JsonNode auditMetadata(Prepared prepared);
    JsonNode publicMetadata(Prepared prepared);
    ExportResult generate(Prepared prepared,Owner owner,Path output,AsyncTaskExecutor.ProgressCallback progress);
}
