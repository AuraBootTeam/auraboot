package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPM Attachment Controller (G-B4).
 *
 * <p>Manages files attached to a BPM task or process instance. Reuses the platform file
 * infrastructure ({@link FileService} over {@code ab_file} + {@code ab_file_relation} with a
 * {@code StorageProvider} backend); no BPM-specific storage table is needed. An attachment is an
 * {@code ab_file} row linked to the task/process via an {@code ab_file_relation} of the
 * corresponding entity type.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/attachments")
@RequiredArgsConstructor
@Tag(name = "BPM Attachments", description = "Attachments on BPM tasks and process instances")
public class BpmAttachmentController {

    private static final String ENTITY_TYPE_TASK = "BPM_TASK";
    private static final String ENTITY_TYPE_PROCESS = "BPM_PROCESS";
    private static final String FIELD_NAME = "attachment";

    private final FileService fileService;

    @PostMapping("/task/{taskId}")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Upload a task attachment")
    public ApiResponse<Map<String, Object>> uploadTaskAttachment(
            @PathVariable String taskId,
            @RequestParam("file") MultipartFile file,
            @CurrentUserId Long userId) {
        return ApiResponse.success(upload(ENTITY_TYPE_TASK, taskId, file, userId));
    }

    @PostMapping("/process/{processInstanceId}")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Upload a process attachment")
    public ApiResponse<Map<String, Object>> uploadProcessAttachment(
            @PathVariable String processInstanceId,
            @RequestParam("file") MultipartFile file,
            @CurrentUserId Long userId) {
        return ApiResponse.success(upload(ENTITY_TYPE_PROCESS, processInstanceId, file, userId));
    }

    @GetMapping("/task/{taskId}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "List task attachments")
    public ApiResponse<List<Map<String, Object>>> getTaskAttachments(@PathVariable String taskId) {
        return ApiResponse.success(list(ENTITY_TYPE_TASK, taskId));
    }

    @GetMapping("/process/{processInstanceId}")
    @RequirePermission(MetaPermission.WORKFLOW_READ)
    @Operation(summary = "List process attachments")
    public ApiResponse<List<Map<String, Object>>> getProcessAttachments(@PathVariable String processInstanceId) {
        return ApiResponse.success(list(ENTITY_TYPE_PROCESS, processInstanceId));
    }

    @DeleteMapping("/{fileId}")
    @RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
    @Operation(summary = "Delete an attachment by file id")
    public ApiResponse<Void> deleteAttachment(@PathVariable String fileId, @CurrentUserId Long userId) {
        boolean removed = fileService.deleteFile(fileId, userId);
        if (!removed) {
            log.warn("BPM attachment delete returned false: fileId={}", fileId);
        }
        return ApiResponse.success();
    }

    private Map<String, Object> upload(String entityType, String entityId, MultipartFile file, Long userId) {
        FileUploadResponseDTO uploaded = fileService.uploadFile(file, userId);

        FileRelationRequestDTO relation = new FileRelationRequestDTO();
        relation.setFileIds(new String[]{uploaded.getFileId()});
        relation.setEntityType(entityType);
        relation.setEntityId(entityId);
        relation.setFieldName(FIELD_NAME);
        fileService.createFileRelation(relation);

        log.info("BPM attachment uploaded: entityType={}, entityId={}, fileId={}",
                entityType, entityId, uploaded.getFileId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", uploaded.getFileId());
        result.put("originalName", uploaded.getOriginalName());
        result.put("fileSize", uploaded.getFileSize());
        result.put("mimeType", uploaded.getMimeType());
        return result;
    }

    private List<Map<String, Object>> list(String entityType, String entityId) {
        List<FileEntity> files = fileService.getFilesByEntity(entityType, entityId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (FileEntity file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileId", file.getPid());
            item.put("originalName", file.getOriginalName());
            item.put("fileName", file.getFileName());
            item.put("fileSize", file.getFileSize());
            item.put("mimeType", file.getMimeType());
            item.put("downloadUrl", fileService.getFileDownloadUrl(file.getPid()));
            result.add(item);
        }
        return result;
    }
}
