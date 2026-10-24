package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DomainExportMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;

/** Retained attempt tombstones also delete objects uploaded late by an already-losing execution. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomainExportArtifactCleanup {
    private final DomainExportMapper attempts;
    private final ExportArtifactStore artifacts;
    private final MeterRegistry metrics;
    @Scheduled(fixedDelayString="${aura.export.domain.cleanup-ms:60000}",initialDelayString="${aura.export.domain.cleanup-initial-ms:60000}")
    public void tick() {
        try {cleanup();}
        // Top-level scheduler boundary: a registry failure remains visible and is retried by the next scheduled scan.
        catch(RuntimeException failure) {
            log.error("Domain export cleanup scan failed",failure);
            metrics.counter("export.domain.cleanup.scan","result","failure").increment();
        }
    }
    public void cleanup() {
        for(var attempt:attempts.dueCleanup()) {
            Long tenant=((Number)attempt.get("tenant_id")).longValue();String pid=(String)attempt.get("pid");
            String failureCode=null;
            try {
                artifacts.delete((String)attempt.get("file_key"));
                Files.deleteIfExists(Path.of((String)attempt.get("temp_path")));
                metrics.counter("export.domain.cleanup","result","success").increment();
            } catch(IOException | RuntimeException failure) {
                // Per-attempt cleanup boundary: keep this exact attempt and its failure for the next scan.
                log.error("Domain export artifact cleanup failed: attempt={}",pid,failure);
                failureCode="ARTIFACT_DELETE_FAILED";metrics.counter("export.domain.cleanup","result","failure").increment();
            }
            if(attempts.cleaned(tenant,pid,failureCode)!=1) throw new IllegalStateException("Domain export cleanup receipt was not persisted");
        }
    }
}
