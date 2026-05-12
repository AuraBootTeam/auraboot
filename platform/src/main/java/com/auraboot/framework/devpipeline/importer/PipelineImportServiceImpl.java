package com.auraboot.framework.devpipeline.importer;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PipelineImportServiceImpl implements PipelineImportService {

    private static final String TARGET_PLUGIN_ID = "com.auraboot.dev-pipeline";
    private static final String TARGET_NAMESPACE = "dpl";
    private static final String PENDING_IMPORT = "pending_plugin_import";
    private static final List<String> IMPORT_ORDER = List.of(
            "dpl_change_artifact",
            "dpl_story",
            "dpl_gate_result",
            "dpl_review",
            "dpl_notification",
            "dpl_decision",
            "dpl_bundle",
            "dpl_fix_task",
            "dpl_evidence",
            "dpl_failure",
            "dpl_schedule_run"
    );
    private static final List<String> KNOWN_MODELS = buildKnownModels();
    private static final Map<String, String> RUN_REFERENCE_FIELDS = Map.ofEntries(
            Map.entry("dpl_change_artifact", "dpl_artifact_run_id"),
            Map.entry("dpl_story", "dpl_story_run_id"),
            Map.entry("dpl_gate_result", "dpl_gate_run_id"),
            Map.entry("dpl_review", "dpl_review_run_id"),
            Map.entry("dpl_notification", "dpl_notification_run_id"),
            Map.entry("dpl_decision", "dpl_decision_run_id"),
            Map.entry("dpl_bundle", "dpl_bundle_run_id"),
            Map.entry("dpl_fix_task", "dpl_fix_run_id"),
            Map.entry("dpl_evidence", "dpl_evidence_run_id"),
            Map.entry("dpl_failure", "dpl_failure_run_id")
    );

    private final ObjectMapper objectMapper;
    private final DynamicDataService dynamicDataService;
    private final PipelineMirrorWriter mirrorWriter;
    private final TransactionOperations transactionOperations;

    public PipelineImportServiceImpl(
            ObjectMapper objectMapper,
            DynamicDataService dynamicDataService,
            PipelineMirrorWriter mirrorWriter,
            PlatformTransactionManager transactionManager
    ) {
        this(objectMapper, dynamicDataService, mirrorWriter, new TransactionTemplate(transactionManager));
    }

    PipelineImportServiceImpl(
            ObjectMapper objectMapper,
            DynamicDataService dynamicDataService,
            PipelineMirrorWriter mirrorWriter,
            TransactionOperations transactionOperations
    ) {
        this.objectMapper = objectMapper;
        this.dynamicDataService = dynamicDataService;
        this.mirrorWriter = mirrorWriter;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public PipelineImportPreview previewPacket(PipelineImportRequest request) {
        ImportPacket packet = readAndValidatePacket(request.packetPath());
        return new PipelineImportPreview(packet.runId(), countRecords(packet.records()));
    }

    @Override
    public PipelineImportResult importFromPacket(PipelineImportRequest request) {
        ImportPacket packet = readAndValidatePacket(request.packetPath());
        if (request.dryRun()) {
            return new PipelineImportResult(
                    packet.runId(),
                    null,
                    PipelineImportStatus.DRY_RUN_OK,
                    countRecords(packet.records()),
                    List.of(),
                    null,
                    null
            );
        }

        PipelineImportResult imported = transactionOperations.execute(status ->
                importRecords(packet, request.conflictStrategy()));
        if (imported == null || imported.status() != PipelineImportStatus.IMPORTED || !request.finalizeMirror()) {
            return imported;
        }

        try {
            Path mirrorPath = mirrorWriter.writeReadOnlyMirror(packet.runDir(), imported.runPid(), Instant.now());
            return new PipelineImportResult(
                    imported.runId(),
                    imported.runPid(),
                    PipelineImportStatus.IMPORTED,
                    imported.recordCounts(),
                    imported.warnings(),
                    mirrorPath,
                    imported.importReportPath()
            );
        } catch (RuntimeException e) {
            List<String> warnings = new ArrayList<>(imported.warnings());
            String finalizeCommand = "node scripts/aura-pipeline/aura-pipeline.mjs finalize-plugin-import "
                    + imported.runId() + " --run-pid " + imported.runPid();
            warnings.add("Mirror write failed; run: " + finalizeCommand);
            return new PipelineImportResult(
                    imported.runId(),
                    imported.runPid(),
                    PipelineImportStatus.MIRROR_PENDING,
                    imported.recordCounts(),
                    List.copyOf(warnings),
                    null,
                    imported.importReportPath()
            );
        }
    }

    private PipelineImportResult importRecords(ImportPacket packet, ConflictStrategy conflictStrategy) {
        Map<String, Object> existing = findExistingRun(packet.runId());
        if (existing != null) {
            String existingPid = stringValue(existing.get("pid"));
            return new PipelineImportResult(
                    packet.runId(),
                    existingPid,
                    conflictStrategy == ConflictStrategy.SKIP
                            ? PipelineImportStatus.SKIPPED
                            : PipelineImportStatus.CONFLICT,
                    countRecords(packet.records()),
                    List.of("Pipeline run already exists: " + packet.runId()),
                    null,
                    null
            );
        }

        Map<String, Object> runRecord = firstRunRecord(packet);
        Map<String, Object> createdRun = dynamicDataService.create("dpl_pipeline_run", new LinkedHashMap<>(runRecord));
        String runPid = requirePid(createdRun, "dpl_pipeline_run");
        Map<String, Integer> importedCounts = new LinkedHashMap<>();
        importedCounts.put("dpl_pipeline_run", 1);

        for (String modelCode : IMPORT_ORDER) {
            List<Map<String, Object>> rows = packet.records().getOrDefault(modelCode, List.of());
            for (Map<String, Object> row : rows) {
                dynamicDataService.create(modelCode, resolveReferences(modelCode, row, packet.runId(), runPid));
            }
            if (!rows.isEmpty()) {
                importedCounts.put(modelCode, rows.size());
            }
        }

        return new PipelineImportResult(
                packet.runId(),
                runPid,
                PipelineImportStatus.IMPORTED,
                importedCounts,
                List.of(),
                null,
                null
        );
    }

    private Map<String, Object> findExistingRun(String runId) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(1)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName("dpl_run_id")
                        .operator(QueryCondition.Operator.EQ)
                        .value(runId)
                        .build()))
                .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list("dpl_pipeline_run", request);
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            return null;
        }
        return result.getRecords().get(0);
    }

    private ImportPacket readAndValidatePacket(Path packetPath) {
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(packetPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read pipeline import packet: " + packetPath, e);
        }

        require(root.path("schemaVersion").asInt() == 1, "Unsupported packet schemaVersion");
        String runId = requireText(root, "runId");
        require(
                TARGET_PLUGIN_ID.equals(root.path("targetPlugin").path("pluginId").asText()),
                "Invalid targetPlugin.pluginId"
        );
        require(
                TARGET_NAMESPACE.equals(root.path("targetPlugin").path("namespace").asText()),
                "Invalid targetPlugin.namespace"
        );
        require(PENDING_IMPORT.equals(root.path("writePolicy").asText()), "Invalid writePolicy");
        require(
                "pass".equals(root.path("validation").path("status").asText()),
                "Packet validation must pass before import"
        );
        validatePacketPath(packetPath, runId);

        JsonNode recordsNode = root.path("records");
        require(recordsNode.isObject(), "records is required");
        Map<String, List<Map<String, Object>>> records = objectMapper.convertValue(
                recordsNode,
                new TypeReference<>() {
                }
        );
        for (String modelCode : records.keySet()) {
            require(KNOWN_MODELS.contains(modelCode), "Unknown dev-pipeline record model: " + modelCode);
        }
        require(
                records.getOrDefault("dpl_pipeline_run", List.of()).size() == 1,
                "Exactly one dpl_pipeline_run record is required"
        );
        return new ImportPacket(runId, packetPath, resolveRunDir(packetPath), records);
    }

    private Map<String, Object> firstRunRecord(ImportPacket packet) {
        return packet.records().get("dpl_pipeline_run").get(0);
    }

    private Map<String, Object> resolveReferences(
            String modelCode,
            Map<String, Object> row,
            String runId,
            String runPid
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>(row);
        String runReferenceField = RUN_REFERENCE_FIELDS.get(modelCode);
        if (runReferenceField != null && runId.equals(resolved.get(runReferenceField))) {
            resolved.put(runReferenceField, runPid);
        }
        if ("dpl_schedule_run".equals(modelCode) && runId.equals(resolved.get("dpl_schedule_related_run_id"))) {
            resolved.put("dpl_schedule_related_run_id", runPid);
        }
        return resolved;
    }

    private Map<String, Integer> countRecords(Map<String, List<Map<String, Object>>> records) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : records.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    private void validatePacketPath(Path packetPath, String runId) {
        Path normalized = packetPath.toAbsolutePath().normalize();
        Path packetDir = normalized.getParent();
        Path evidenceDir = packetDir == null ? null : packetDir.getParent();
        Path runDir = evidenceDir == null ? null : evidenceDir.getParent();
        Path pipelineDir = runDir == null ? null : runDir.getParent();
        require(Files.exists(normalized), "Packet file does not exist");
        require(
                "import-packets".equals(fileName(packetDir)),
                "Packet must be under evidence/import-packets"
        );
        require(
                "evidence".equals(fileName(evidenceDir)),
                "Packet must be under evidence/import-packets"
        );
        require(
                runId.equals(fileName(runDir)),
                "Packet run directory must match runId"
        );
        require(
                "pipeline".equals(fileName(pipelineDir)),
                "Packet must be under pipeline/<run-id>"
        );
    }

    private Path resolveRunDir(Path packetPath) {
        return packetPath.toAbsolutePath().normalize().getParent().getParent().getParent();
    }

    private String requireText(JsonNode root, String fieldName) {
        String value = root.path(fieldName).asText();
        require(value != null && !value.isBlank(), fieldName + " is required");
        return value;
    }

    private String requirePid(Map<String, Object> created, String modelCode) {
        Object pid = created.get("pid");
        require(pid != null, modelCode + " create result must include pid");
        return pid.toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static List<String> buildKnownModels() {
        List<String> models = new ArrayList<>();
        models.add("dpl_pipeline_run");
        models.addAll(IMPORT_ORDER);
        return List.copyOf(models);
    }

    private record ImportPacket(
            String runId,
            Path packetPath,
            Path runDir,
            Map<String, List<Map<String, Object>>> records
    ) {
    }
}
