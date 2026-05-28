package com.auraboot.framework.semantic.service;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.semantic.dto.*;
import com.auraboot.framework.semantic.entity.*;
import com.auraboot.framework.semantic.enums.SemanticModelStatus;
import com.auraboot.framework.semantic.mapper.*;
import com.auraboot.framework.semantic.parser.SemanticYamlValidator;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Drives the full publication pipeline:
 * <pre>
 *   YAML bytes
 *     → SemanticYamlParser (schema validation)
 *     → SemanticYamlValidator  (business rules + injection denylist)
 *     → upsert into ab_semantic_{model,dimension,metric}
 *     → rebuild ab_semantic_lineage_edge for this model
 *     → mark status = ACTIVE
 * </pre>
 *
 * <p>Idempotent on yaml SHA-256: re-publishing the same source is a no-op.
 *
 * <p>For W2 D4-5 this is invoked directly via API; plugin lifecycle wiring
 * (resourceDirs.semantic auto-scan) is registered separately by
 * SemanticPluginResourceImporter (also in this commit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticPublishService {

    private final SemanticYamlParser parser;
    private final SemanticYamlValidator validator;
    private final AbSemanticModelMapper modelMapper;
    private final AbSemanticDimensionMapper dimensionMapper;
    private final AbSemanticMetricMapper metricMapper;
    private final AbSemanticLineageEdgeMapper lineageMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Parse, validate, and persist one {@code *.semantic.yml} source.
     *
     * @return the pid of the resulting (created or updated) {@link AbSemanticModel}
     */
    @Transactional
    public String publishFromYaml(byte[] yamlBytes, String pluginCode, Long tenantId, Long userId) {
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8);
        SemanticModelDTO dto = parser.parse(yaml);
        validator.validate(dto);

        String yamlSha = sha256(yaml);
        String version = dto.getVersion();
        String code = dto.getSemanticModel().getCode();

        AbSemanticModel existing = modelMapper.findByCode(tenantId, pluginCode, code, version);
        if (existing != null && yamlSha.equals(existing.getYamlSha())) {
            log.debug("Semantic model already at sha {}, skipping: {}/{}", yamlSha, pluginCode, code);
            return existing.getPid();
        }

        String modelPid = existing != null ? existing.getPid() : UlidGenerator.generate();

        AbSemanticModel record = existing != null ? existing : new AbSemanticModel();
        record.setPid(modelPid);
        record.setTenantId(tenantId);
        record.setPluginCode(pluginCode);
        record.setCode(code);
        record.setModelRef(dto.getSemanticModel().getModelRef());
        record.setPrimaryEntity(dto.getSemanticModel().getPrimaryEntity());
        record.setLabelI18n(toJson(dto.getSemanticModel().getLabel()));
        record.setDescription(dto.getSemanticModel().getDescription());
        record.setVersion(version);
        record.setStatus(SemanticModelStatus.ACTIVE.name());
        record.setYamlSource(yaml);
        record.setYamlSha(yamlSha);
        record.setUpdatedBy(userId);
        if (existing == null) {
            record.setCreatedBy(userId);
            modelMapper.insert(record);
        } else {
            modelMapper.updateById(record);
        }

        persistDimensions(dto, modelPid, tenantId);
        persistMetrics(dto, modelPid, tenantId, userId);
        rebuildLineage(dto, modelPid, tenantId);

        log.info("Published semantic model {}/{}@{} pid={} ({} dims, {} metrics)",
                pluginCode, code, version, modelPid,
                dto.getDimensions().size(), dto.getMetrics().size());
        return modelPid;
    }

    // -- dimensions ------------------------------------------------------------

    private void persistDimensions(SemanticModelDTO dto, String modelPid, Long tenantId) {
        // Simple strategy for v0.1: soft-delete any existing dims under this model, then re-insert.
        // OK because semantic.yml is the source of truth and re-imports are infrequent.
        List<AbSemanticDimension> existing = dimensionMapper.listByModel(tenantId, modelPid);
        for (AbSemanticDimension d : existing) {
            d.setDeletedFlag(Boolean.TRUE);
            dimensionMapper.updateById(d);
        }
        for (DimensionDTO d : dto.getDimensions()) {
            AbSemanticDimension row = new AbSemanticDimension();
            row.setPid(UlidGenerator.generate());
            row.setTenantId(tenantId);
            row.setSemanticModelPid(modelPid);
            row.setCode(d.getCode());
            row.setFieldRef(d.getFieldRef());
            row.setDimType(d.getType());
            row.setLabelI18n(toJson(d.getLabel()));
            row.setDescription(d.getDescription());
            row.setTimeGrains(toJson(d.getTimeGrains()));
            row.setPrimaryTime(Boolean.TRUE.equals(d.getPrimaryTime()));
            dimensionMapper.insert(row);
        }
    }

    // -- metrics ---------------------------------------------------------------

    private void persistMetrics(SemanticModelDTO dto, String modelPid, Long tenantId, Long userId) {
        // Soft-delete existing
        List<AbSemanticMetric> existing = metricMapper.listActiveByModel(tenantId, modelPid);
        for (AbSemanticMetric m : existing) {
            m.setDeletedFlag(Boolean.TRUE);
            metricMapper.updateById(m);
        }
        for (MetricDTO m : dto.getMetrics()) {
            AbSemanticMetric row = new AbSemanticMetric();
            row.setPid(UlidGenerator.generate());
            row.setTenantId(tenantId);
            row.setSemanticModelPid(modelPid);
            row.setCode(m.getCode());
            row.setMetricType(m.getType().toLowerCase());
            row.setTypeParams(toJson(m.getTypeParams()));
            row.setFilterExpr(m.getFilter());
            row.setLabelI18n(toJson(m.getLabel()));
            row.setDescription(m.getDescription());
            row.setRequiredPermissions(toJson(m.getRequiredPermissions()));
            row.setStatus(SemanticModelStatus.ACTIVE.name());
            row.setVersion(dto.getVersion());
            row.setCreatedBy(userId);
            row.setUpdatedBy(userId);
            metricMapper.insert(row);
        }
    }

    // -- lineage ---------------------------------------------------------------

    /**
     * Lineage policy for v0.1 (simplified):
     * <ul>
     *   <li>Each metric → model (depends_on)</li>
     *   <li>Each metric → measure code(s) it references (measure_ref) — recorded as
     *       string code keyed by model pid; in v0.2 we resolve measure_ref to a
     *       distinct ab_semantic_measure table.</li>
     *   <li>Derived metric → metric placeholders (depends_on with input_metric ref)</li>
     * </ul>
     *
     * <p>The simplification: edges store code strings in dst_node_pid for measure refs
     * because v0.1 does not have a dedicated measure table. v0.2 will normalize.
     */
    private void rebuildLineage(SemanticModelDTO dto, String modelPid, Long tenantId) {
        lineageMapper.softDeleteAllFrom(tenantId, modelPid);
        for (MetricDTO m : dto.getMetrics()) {
            // metric → model
            AbSemanticLineageEdge edge = new AbSemanticLineageEdge();
            edge.setPid(UlidGenerator.generate());
            edge.setTenantId(tenantId);
            edge.setSrcNodePid(modelPid + ":metric:" + m.getCode());
            edge.setSrcNodeType("metric");
            edge.setDstNodePid(modelPid);
            edge.setDstNodeType("model");
            edge.setRefType("depends_on");
            lineageMapper.insert(edge);

            // metric → measure (simple/cumulative)
            Map<String, Object> p = m.getTypeParams();
            String type = m.getType();
            if ("simple".equals(type) || "cumulative".equals(type)) {
                addMeasureEdge(modelPid, tenantId, m.getCode(), String.valueOf(p.get("measure")));
            } else if ("ratio".equals(type)) {
                addMeasureEdge(modelPid, tenantId, m.getCode(), String.valueOf(p.get("numerator")));
                addMeasureEdge(modelPid, tenantId, m.getCode(), String.valueOf(p.get("denominator")));
            } else if ("conversion".equals(type)) {
                addMeasureEdge(modelPid, tenantId, m.getCode(), String.valueOf(p.get("base_measure")));
                addMeasureEdge(modelPid, tenantId, m.getCode(), String.valueOf(p.get("conversion_measure")));
            }
            // derived → other metrics
            if ("derived".equals(type)) {
                String expr = String.valueOf(p.get("expr"));
                java.util.regex.Matcher mt = java.util.regex.Pattern
                        .compile("\\{([a-z][a-z0-9_]*)\\}").matcher(expr);
                while (mt.find()) {
                    AbSemanticLineageEdge dep = new AbSemanticLineageEdge();
                    dep.setPid(UlidGenerator.generate());
                    dep.setTenantId(tenantId);
                    dep.setSrcNodePid(modelPid + ":metric:" + m.getCode());
                    dep.setSrcNodeType("metric");
                    dep.setDstNodePid(modelPid + ":ref:" + mt.group(1));
                    dep.setDstNodeType("metric");
                    dep.setRefType("input_metric");
                    lineageMapper.insert(dep);
                }
            }
        }
    }

    private void addMeasureEdge(String modelPid, Long tenantId, String metricCode, String measureCode) {
        AbSemanticLineageEdge edge = new AbSemanticLineageEdge();
        edge.setPid(UlidGenerator.generate());
        edge.setTenantId(tenantId);
        edge.setSrcNodePid(modelPid + ":metric:" + metricCode);
        edge.setSrcNodeType("metric");
        edge.setDstNodePid(modelPid + ":measure:" + measureCode);
        edge.setDstNodeType("measure");
        edge.setRefType("measure_ref");
        lineageMapper.insert(edge);
    }

    // -- helpers ---------------------------------------------------------------

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("toJson failed: {}", e.getMessage());
            return null;
        }
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
