package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.BusinessTargetAuthorization;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable effective field view for artifact generation, distinct from internal business facts. */
public record CommandPublicOutputProjectionPlan(BusinessTargetAuthorization target, BusinessGrantDecision grant,
                                                 Map<String, ModelFields> models) {
    public CommandPublicOutputProjectionPlan { models = Map.copyOf(models); }

    public record ModelFields(Set<String> declared, Set<String> readable,
            List<BusinessResultProjectionPlan.PolicyMask> policyMasks,
            List<BusinessResultProjectionPlan.DetailMask> detailMasks) {
        public ModelFields {
            declared = Set.copyOf(declared);
            readable = Set.copyOf(readable);
            policyMasks = List.copyOf(policyMasks);
            detailMasks = List.copyOf(detailMasks);
            if (declared.isEmpty() || !declared.containsAll(readable)) throw BusinessResultProjectionPlan.denied();
        }
    }

    /** Hash only the effective public view, not raw records, role IDs, or grant source IDs. */
    public String fingerprint() {
        Map<String, Object> canonical = new TreeMap<>();
        models.forEach((model, fields) -> canonical.put(model, Map.of(
                "declared", fields.declared().stream().sorted().toList(),
                "readable", fields.readable().stream().sorted().toList(),
                "policyMasks", fields.policyMasks(), "detailMasks", fields.detailMasks())));
        try {
            String json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(Map.of("version", 1, "models", canonical));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint the public output policy", failure);
        }
    }
}
