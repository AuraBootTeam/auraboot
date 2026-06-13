package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutPolicyDTO;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.service.DecisionGovernanceQueryService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds lightweight governance projections from the existing Decision Runtime tables.
 */
@Service
@RequiredArgsConstructor
public class DecisionGovernanceQueryServiceImpl implements DecisionGovernanceQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final DrtVersionMapper versionMapper;

    @Override
    public PageResult<DecisionRolloutPolicyDTO> listRollouts(String decisionCode, int page, int size) {
        List<DrtVersionEntity> versions = findVersions(decisionCode);
        Map<String, List<DrtVersionEntity>> byCode = versions.stream()
                .collect(Collectors.groupingBy(DrtVersionEntity::getDecisionCode, TreeMap::new, Collectors.toList()));

        List<DecisionRolloutPolicyDTO> rows = byCode.entrySet().stream()
                .map(entry -> toRolloutRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DecisionRolloutPolicyDTO::getDecisionCode))
                .collect(Collectors.toList());

        return page(rows, page, size);
    }

    @Override
    public PageResult<DecisionModelFieldDTO> listModelFields(String decisionCode, int page, int size) {
        List<DrtVersionEntity> versions = findVersions(decisionCode);
        Map<String, FieldAgg> fields = new TreeMap<>();

        for (DrtVersionEntity version : versions) {
            JsonNode refs = version.getFieldRefsJson();
            if (refs == null || !refs.isArray()) {
                continue;
            }
            for (JsonNode refNode : refs) {
                String ref = refNode.asText(null);
                if (!StringUtils.hasText(ref)) {
                    continue;
                }
                FieldAgg agg = fields.computeIfAbsent(ref, FieldAgg::new);
                agg.refs++;
                agg.decisionCodes.add(version.getDecisionCode());
            }
        }

        List<DecisionModelFieldDTO> rows = fields.values().stream()
                .map(FieldAgg::toDTO)
                .collect(Collectors.toList());
        return page(rows, page, size);
    }

    private List<DrtVersionEntity> findVersions(String decisionCode) {
        Long tenantId = requireTenant();
        LambdaQueryWrapper<DrtVersionEntity> query = new LambdaQueryWrapper<>();
        query.eq(DrtVersionEntity::getTenantId, tenantId);
        if (StringUtils.hasText(decisionCode)) {
            query.eq(DrtVersionEntity::getDecisionCode, decisionCode.trim());
        }
        query.orderByDesc(DrtVersionEntity::getCreatedAt);
        return versionMapper.selectList(query);
    }

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision governance data not found");
        }
        return tid;
    }

    private DecisionRolloutPolicyDTO toRolloutRow(String decisionCode, List<DrtVersionEntity> versions) {
        List<DrtVersionEntity> sorted = new ArrayList<>(versions);
        sorted.sort(Comparator.comparing(DrtVersionEntity::getVersion, Comparator.nullsLast(Integer::compareTo)).reversed());

        DrtVersionEntity baseline = sorted.stream()
                .filter(v -> VersionStatus.PUBLISHED.name().equals(v.getStatus()))
                .findFirst()
                .orElse(null);
        DrtVersionEntity candidate = sorted.stream()
                .filter(v -> !VersionStatus.PUBLISHED.name().equals(v.getStatus()))
                .findFirst()
                .orElse(null);
        DrtVersionEntity anchor = candidate != null ? candidate : baseline != null ? baseline : sorted.stream().findFirst().orElse(null);

        DecisionRolloutPolicyDTO row = new DecisionRolloutPolicyDTO();
        row.setPid(anchor != null ? anchor.getPid() : decisionCode);
        row.setDecisionCode(decisionCode);
        row.setBaselineVersion(baseline != null ? baseline.getVersion() : null);
        row.setCandidateVersion(candidate != null ? candidate.getVersion() : null);
        row.setStatus(resolveRolloutStatus(baseline, candidate));
        row.setPercentage(0.0);
        row.setRoutingKeyExpr("hash(traceId/recordId)");
        row.setStartedAt(baseline != null ? baseline.getPublishedAt() : null);
        row.setUpdatedAt(latestInstant(sorted));
        return row;
    }

    private String resolveRolloutStatus(DrtVersionEntity baseline, DrtVersionEntity candidate) {
        if (candidate != null && baseline != null) {
            return "CANDIDATE_READY";
        }
        if (candidate != null) {
            return "NO_BASELINE";
        }
        if (baseline != null) {
            return "PUBLISHED_ONLY";
        }
        return "NO_VERSION";
    }

    private Instant latestInstant(List<DrtVersionEntity> versions) {
        return versions.stream()
                .map(v -> v.getPublishedAt() != null ? v.getPublishedAt() : v.getCreatedAt())
                .filter(ts -> ts != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private <T> PageResult<T> page(List<T> rows, int requestedPage, int requestedSize) {
        int safePage = Math.max(requestedPage, 1);
        int safeSize = Math.min(Math.max(requestedSize, 1), MAX_PAGE_SIZE);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new PageResult<>(rows.subList(from, to), (long) rows.size(), (long) safeSize, (long) safePage);
    }

    private static final class FieldAgg {
        private final String ref;
        private long refs;
        private final LinkedHashSet<String> decisionCodes = new LinkedHashSet<>();

        private FieldAgg(String ref) {
            this.ref = ref;
        }

        private DecisionModelFieldDTO toDTO() {
            ParsedField parsed = ParsedField.from(ref);
            DecisionModelFieldDTO dto = new DecisionModelFieldDTO();
            dto.setEntityCode(parsed.entityCode);
            dto.setPath(parsed.path);
            dto.setLabel(parsed.label);
            dto.setDataType("unknown");
            dto.setRefs(refs);
            dto.setMasked(false);
            dto.setPermission(null);
            dto.setDecisionCodes(new ArrayList<>(decisionCodes));
            return dto;
        }
    }

    private record ParsedField(String entityCode, String path, String label) {
        private static ParsedField from(String ref) {
            String[] parts = ref.split("\\.", 2);
            String entity = parts.length > 1 && StringUtils.hasText(parts[0]) ? parts[0] : "context";
            String path = parts.length > 1 ? parts[1] : ref;
            String[] pathParts = path.split("\\.");
            String label = pathParts.length == 0 ? path : pathParts[pathParts.length - 1];
            return new ParsedField(entity, path, toTitle(label));
        }

        private static String toTitle(String value) {
            if (!StringUtils.hasText(value)) {
                return value;
            }
            String normalized = value.replace('_', ' ').replace('-', ' ').trim();
            if (normalized.isEmpty()) {
                return value;
            }
            return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
        }
    }
}
