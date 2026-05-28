package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.entity.AbSemanticDimension;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.enums.SemanticModelStatus;
import com.auraboot.framework.semantic.mapper.AbSemanticDimensionMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only catalog service backing {@code GET /api/semantic/meta}.
 *
 * <p>Lists every ACTIVE {@link AbSemanticModel} in the tenant and inlines
 * its metrics + dimensions. JSON columns are decoded from text storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCatalogService {

    private final AbSemanticModelMapper modelMapper;
    private final AbSemanticMetricMapper metricMapper;
    private final AbSemanticDimensionMapper dimensionMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private static final TypeReference<Map<String, String>> LABEL_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};

    public SemanticMetaResponse listCatalog(Long tenantId) {
        SemanticMetaResponse out = new SemanticMetaResponse();
        List<AbSemanticModel> models = modelMapper.listActiveByTenant(tenantId);
        for (AbSemanticModel m : models) {
            out.getModels().add(toModelMeta(m, tenantId));
        }
        return out;
    }

    private SemanticMetaResponse.ModelMeta toModelMeta(AbSemanticModel m, Long tenantId) {
        SemanticMetaResponse.ModelMeta dto = new SemanticMetaResponse.ModelMeta();
        dto.setPid(m.getPid());
        dto.setCode(m.getCode());
        dto.setPluginCode(m.getPluginCode());
        dto.setVersion(m.getVersion());
        dto.setLabel(parseLabel(m.getLabelI18n()));
        dto.setDescription(m.getDescription());
        dto.setModelRef(m.getModelRef());

        for (AbSemanticMetric metric :
                metricMapper.listActiveByModel(tenantId, m.getPid())) {
            SemanticMetaResponse.MetricMeta mm = new SemanticMetaResponse.MetricMeta();
            mm.setPid(metric.getPid());
            mm.setCode(metric.getCode());
            mm.setType(metric.getMetricType());
            mm.setLabel(parseLabel(metric.getLabelI18n()));
            mm.setDescription(metric.getDescription());
            mm.setRequiredPermissions(parseList(metric.getRequiredPermissions()));
            dto.getMetrics().add(mm);
        }
        for (AbSemanticDimension dim :
                dimensionMapper.listByModel(tenantId, m.getPid())) {
            SemanticMetaResponse.DimensionMeta dm = new SemanticMetaResponse.DimensionMeta();
            dm.setPid(dim.getPid());
            dm.setCode(dim.getCode());
            dm.setType(dim.getDimType());
            dm.setLabel(parseLabel(dim.getLabelI18n()));
            dm.setTimeGrains(parseList(dim.getTimeGrains()));
            dm.setPrimaryTime(Boolean.TRUE.equals(dim.getPrimaryTime()));
            dto.getDimensions().add(dm);
        }
        return dto;
    }

    private Map<String, String> parseLabel(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return jsonMapper.readValue(json, LABEL_TYPE);
        } catch (IOException e) {
            log.warn("Failed to parse label_i18n: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return jsonMapper.readValue(json, STRING_LIST_TYPE);
        } catch (IOException e) {
            log.warn("Failed to parse list json: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
