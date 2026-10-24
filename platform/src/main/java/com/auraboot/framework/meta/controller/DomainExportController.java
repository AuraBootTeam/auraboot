package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.ExportTaskDTO;
import com.auraboot.framework.meta.service.impl.DomainExportTaskService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owner and source permissions are checked for every status and artifact request. Submission remains business-command only. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exports/domain")
@com.auraboot.framework.permission.annotation.AuthenticatedAccess("Own export handles only; the registered source enforces current business permissions, original membership and captured row scope")
public class DomainExportController {
    private final DomainExportTaskService exports;
    @GetMapping("/{taskPid}")
    public ApiResponse<ExportTaskDTO> status(@PathVariable String taskPid){return ApiResponse.success(exports.status(taskPid));}
    @GetMapping("/{taskPid}/download")
    public void download(@PathVariable String taskPid,HttpServletResponse response) throws IOException {
        var artifact=exports.download(taskPid);
        try(var content=artifact.content()) {
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
            response.setContentType("text/csv; charset=UTF-8");response.setContentLengthLong(artifact.size());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(artifact.fileName(),StandardCharsets.UTF_8).build().toString());
            content.transferTo(response.getOutputStream());
        }
    }
}
