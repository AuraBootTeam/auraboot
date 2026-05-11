package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executor for {@code sourceType=namedQuery} virtual models.
 *
 * <p>Resolves the model's {@code sourceRef} as a NamedQuery code, then delegates to
 * {@link DynamicDataService#listByQueryCode(String, DynamicQueryRequest)} — which
 * internally calls {@code NamedQueryService.executeQuery} and routes through the
 * tenant / permission / audit pipeline. This executor must not bypass that pipeline.
 *
 * <p>{@link #get(String, Object)} uses the declared {@code detailKeyField} (default:
 * primary key), appends an {@code EQ} filter, and runs the list path with
 * {@code pageSize=1}, returning the first record. This phase-1 approach avoids
 * introducing a separate detail-query contract.
 *
 * <p>The {@link DynamicDataService} is looked up lazily via {@link ApplicationContext}
 * to break the circular dependency
 * {@code DynamicDataServiceImpl -> ExecutorRegistry -> NamedQueryModelExecutor -> DynamicDataService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NamedQueryModelExecutor implements ModelDataExecutor {

    private final MetaModelService metaModelService;
    private final ApplicationContext applicationContext;

    private DynamicDataService getDynamicDataService() {
        return applicationContext.getBean(DynamicDataService.class);
    }

    @Override
    public String sourceType() {
        return "namedQuery";
    }

    @Override
    public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
        ModelDefinition def = requireDefinition(modelCode);
        String queryCode = requireSourceRef(def);
        // lgtm[java/log-injection] DSL model/query codes are structured metadata identifiers and are logged as parameters only.
        log.debug("NamedQueryModelExecutor.list: model={}, queryCode={}", modelCode, queryCode);
        return getDynamicDataService().listByQueryCode(queryCode, request);
    }

    @Override
    public Map<String, Object> get(String modelCode, Object primaryKeyValue) {
        ModelDefinition def = requireDefinition(modelCode);
        String queryCode = requireSourceRef(def);

        ModelCapabilities caps = def.getCapabilities();
        String pkField = caps != null
            ? caps.resolveDetailKeyField(def.getPrimaryKey())
            : def.getPrimaryKey();
        if (pkField == null || pkField.isBlank()) {
            throw new MetaServiceException(
                "namedQuery virtual model missing primaryKey/detailKeyField: " + modelCode);
        }

        QueryCondition pkCondition = QueryCondition.builder()
            .fieldName(pkField)
            .operator(QueryCondition.Operator.EQ)
            .value(primaryKeyValue)
            .build();

        List<QueryCondition> conditions = new ArrayList<>();
        conditions.add(pkCondition);

        DynamicQueryRequest filterReq = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(1)
            .conditions(conditions)
            .build();

        log.debug("NamedQueryModelExecutor.get: model={}, queryCode={}, pkField={}, pkValue={}",
            modelCode, queryCode, pkField, primaryKeyValue);

        PaginationResult<Map<String, Object>> result =
            getDynamicDataService().listByQueryCode(queryCode, filterReq);
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            return null;
        }
        return result.getRecords().get(0);
    }

    private ModelDefinition requireDefinition(String modelCode) {
        ModelDefinition def = metaModelService.getDefinitionByCode(modelCode);
        if (def == null) {
            throw new MetaServiceException("Model definition not found: " + modelCode);
        }
        return def;
    }

    private String requireSourceRef(ModelDefinition def) {
        String ref = def.getSourceRef();
        if (ref == null || ref.isBlank()) {
            throw new MetaServiceException(
                "namedQuery virtual model missing sourceRef: " + def.getCode());
        }
        return ref;
    }
}
