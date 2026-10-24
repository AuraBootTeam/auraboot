package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.workbench.service.AnnouncementReaderService;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.file.support.FileNameEncodingSupport;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Recipient routes reauthorize the exact sealed version on every read and download. */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@AuthenticatedAccess("All routes require the caller's enabled membership and exact sealed recipient version; GET does not mark read")
public class AnnouncementReaderController {
    private final AnnouncementReaderService readers;
    private final FileService files;
    private final StorageProvider storage;
    private final com.auraboot.framework.audit.service.AdminEventLogService audit;

    @GetMapping("/received")
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="all") String filter,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(page<0 || page>99999) throw com.auraboot.framework.exception.BusinessException.i18n("announcement.error.invalid_input");
        return ApiResponse.success(readers.list(keyword,filter,page+1,size));
    }
    @GetMapping("/versions/{version}/recipient")
    public ApiResponse<Map<String,Object>> detail(@PathVariable String version) {return ApiResponse.success(readers.detail(version));}
    @PostMapping("/versions/{version}/read")
    public ApiResponse<Map<String,Object>> read(@PathVariable String version,@RequestBody Map<String,Object> input) {return ApiResponse.success(readers.mark(version,input,false));}
    @PostMapping("/versions/{version}/acknowledge")
    public ApiResponse<Map<String,Object>> acknowledge(@PathVariable String version,@RequestBody Map<String,Object> input) {return ApiResponse.success(readers.mark(version,input,true));}
    @GetMapping("/versions/{version}/attachments/{file}")
    public ResponseEntity<Resource> attachment(@PathVariable String version,@PathVariable String file) {
        if(!readers.attachmentAllowed(version,file)) throw new org.springframework.security.access.AccessDeniedException("File is outside this frozen announcement");
        var metadata=files.getFileById(file);
        if(metadata==null || Boolean.TRUE.equals(metadata.getDeletedFlag()) || !"success".equals(metadata.getStatus())) return ResponseEntity.notFound().build();
        String key=metadata.getLocalPath();if(key==null || key.isBlank()) key=metadata.getCloudKey();
        if(key==null || key.isBlank()) throw new IllegalStateException("Retained announcement file has no storage key");
        Resource body=new InputStreamResource(storage.download(key));
        audit.record(com.auraboot.framework.audit.entity.AdminEventLog.builder().actorUserId(MetaContext.getCurrentUserId())
            .actionType("announcement.attachment.download").resourceType("announcement_version").resourcePid(version)
            .success(true).reason("Authorized frozen recipient attachment").build());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,FileNameEncodingSupport.contentDisposition("attachment",metadata.getOriginalName()))
            .body(body);
    }
}
