package com.auraboot.framework.dataquality.dbt;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * REST surface for dbt artifact ingest.
 *
 * <p>Accepts a {@code manifest.json} upload and an optional {@code catalog.json},
 * parses them via {@link DbtManifestParser}, then delegates to
 * {@link DbtLineageIngestService} to write edges into {@code ab_semantic_lineage_edge}.
 *
 * <p>Permission: {@code meta.chatbi.use} (temporary; a dedicated
 * {@code meta.dataquality.*} permission set is planned as a follow-up).
 *
 * <p>Endpoint: {@code POST /api/dataquality/dbt/ingest}
 */
@Slf4j
@RestController
@RequestMapping("/api/dataquality/dbt")
@RequiredArgsConstructor
public class DbtIngestController {

    private final DbtManifestParser parser;
    private final DbtLineageIngestService ingestService;

    /**
     * Upload and ingest a dbt {@code manifest.json}, with an optional {@code catalog.json}.
     *
     * @param manifest  required — the dbt {@code manifest.json} file
     * @param catalog   optional — the dbt {@code catalog.json} file; may be omitted
     * @return JSON object with {@code ok: true} and {@code edgesInserted} count
     */
    @PostMapping(value = "/ingest", consumes = {"multipart/form-data"})
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public Map<String, Object> ingest(
            @RequestParam("file") MultipartFile manifest,
            @RequestParam(value = "catalog", required = false) MultipartFile catalog) throws IOException {

        Long tenantId = MetaContext.get().getTenantId();

        byte[] manifestBytes = manifest.getBytes();
        byte[] catalogBytes = (catalog != null && !catalog.isEmpty()) ? catalog.getBytes() : null;

        DbtArtifact.DbtManifest parsedManifest = parser.parseManifest(manifestBytes);
        // Parse catalog for future column enrichment (result currently unused in ingest).
        parser.parseCatalog(catalogBytes);

        int edgesInserted = ingestService.ingest(tenantId, parsedManifest);

        log.info("dbt ingest complete: tenant={} edgesInserted={}", tenantId, edgesInserted);
        return Map.of("ok", true, "edgesInserted", edgesInserted);
    }
}
