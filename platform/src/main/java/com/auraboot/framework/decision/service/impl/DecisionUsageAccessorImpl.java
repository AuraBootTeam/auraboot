package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.rule.RuleReferenceCollector;
import com.auraboot.framework.decision.rule.RuleReferenceSet;
import com.auraboot.framework.plugin.extension.DecisionUsageAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DecisionUsageAccessorImpl implements DecisionUsageAccessor {

    private final DecisionUsageRefMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void replaceSource(Source source, List<Fragment> fragments) {
        Long tenantId = MetaContext.getCurrentTenantId();
        mapper.deleteBySource(tenantId, source.type(), source.pid());
        Map<String, DecisionUsageRefEntity> unique = new LinkedHashMap<>();
        for (Fragment fragment : fragments == null ? List.<Fragment>of() : fragments) {
            RuleReferenceSet refs = RuleReferenceCollector.collect(objectMapper.valueToTree(fragment.content()));
            refs.decisionRefs().forEach(code -> add(unique, ref(tenantId, source, fragment,
                    "DECISION", code, fragment.targetPath())));
            refs.fieldRefs().forEach(path -> add(unique, ref(tenantId, source, fragment,
                    "FIELD", null, path)));
            refs.conditionFragmentRefs().forEach(code -> add(unique, ref(tenantId, source, fragment,
                    "CONDITION_FRAGMENT", code, fragment.targetPath())));
        }
        unique.values().forEach(mapper::insert);
    }

    @Override
    @Transactional
    public void deleteSource(String sourceType, String sourcePid) {
        mapper.deleteBySource(MetaContext.getCurrentTenantId(), sourceType.trim().toUpperCase(), sourcePid);
    }

    private void add(Map<String, DecisionUsageRefEntity> refs, DecisionUsageRefEntity ref) {
        String key = String.join("\u001f", value(ref.getTargetType()), value(ref.getTargetCode()),
                value(ref.getTargetPath()), value(ref.getBinding()));
        refs.putIfAbsent(key, ref);
    }

    private DecisionUsageRefEntity ref(Long tenantId, Source source, Fragment fragment,
                                       String targetType, String targetCode, String targetPath) {
        Instant now = Instant.now();
        DecisionUsageRefEntity entity = new DecisionUsageRefEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setSourceType(source.type());
        entity.setSourceCode(source.code());
        entity.setSourceVersion(source.version());
        entity.setSourcePid(source.pid());
        entity.setTargetType(targetType);
        entity.setTargetCode(targetCode);
        entity.setTargetPath(targetPath);
        entity.setBinding(fragment.binding());
        JsonNode metadata = objectMapper.valueToTree(fragment.metadata());
        entity.setMetadataJson(metadata);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
