package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.entity.BpmSignatureRecord;
import com.auraboot.framework.bpm.service.BpmSignatureService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for BPM signature operations (placeholder).
 */
@RestController
@RequestMapping("/api/bpm/signatures")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_SIGNATURE_MANAGE)
public class BpmSignatureController {

    private final BpmSignatureService signatureService;

    /**
     * Create a signature for a document.
     */
    @PostMapping("/sign")
    public ResponseEntity<BpmSignatureRecord> sign(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(signatureService.sign(request));
    }

    /**
     * Verify a document's signatures.
     */
    @GetMapping("/verify/{documentId}")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String documentId) {
        return ResponseEntity.ok(signatureService.verify(documentId));
    }

    /**
     * Get signature records for a process instance.
     */
    @GetMapping("/records/{processInstanceId}")
    public ResponseEntity<List<BpmSignatureRecord>> getRecords(@PathVariable String processInstanceId) {
        return ResponseEntity.ok(signatureService.getRecordsByProcess(processInstanceId));
    }
}
