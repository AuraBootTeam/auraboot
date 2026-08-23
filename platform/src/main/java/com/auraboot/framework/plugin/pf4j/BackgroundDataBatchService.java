package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.FieldWriterGuard;
import com.auraboot.framework.meta.service.impl.ModelMutationGuard;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Host-owned database implementation for bounded background scans and lease claims. */
@Service
class BackgroundDataBatchService {

    private static final String PHYSICAL_SOURCE = "physical";
    private static final String JSON = "json";
    private static final String JSONB = "jsonb";

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    BackgroundDataBatchService(DynamicDataService dynamicDataService,
                               MetaModelService metaModelService,
                               DynamicDataMapper dynamicDataMapper) {
        this.dynamicDataService = dynamicDataService;
        this.metaModelService = metaModelService;
        this.dynamicDataMapper = dynamicDataMapper;
    }

    BackgroundDataAccessor.BoundedPage queryPage(String modelCode,
                                                 Map<String, Object> exactFilters,
                                                 String afterRecordPid,
                                                 int limit) {
        requireLimit(limit);
        ModelDefinition model = requirePhysicalDynamicModel(modelCode);
        List<QueryCondition> conditions = new ArrayList<>();
        if (exactFilters != null) {
            for (Map.Entry<String, Object> filter : exactFilters.entrySet()) {
                resolveStoredField(model, filter.getKey());
                conditions.add(QueryCondition.builder()
                        .fieldName(filter.getKey())
                        .operator(QueryCondition.Operator.EQ)
                        .value(filter.getValue())
                        .build());
            }
        }
        // An empty cursor deliberately selects pid > ''. It forces the existing physical-model
        // list path into DB keyset mode even for the first page, instead of OFFSET pagination.
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(limit)
                .cursor(afterRecordPid == null ? "" : afterRecordPid)
                .conditions(conditions)
                .build();
        PaginationResult<Map<String, Object>> page = dynamicDataService.list(modelCode, request);
        List<Map<String, Object>> records = page.getRecords() == null
                ? List.of() : List.copyOf(page.getRecords());
        if (records.size() > limit) {
            throw new IllegalStateException("DB-native bounded page exceeded requested limit");
        }
        String nextCursor = null;
        if (records.size() == limit) {
            Object lastPid = records.getLast().get("pid");
            if (lastPid == null || String.valueOf(lastPid).isBlank()) {
                throw new IllegalStateException("bounded page record is missing public pid");
            }
            nextCursor = String.valueOf(lastPid);
        }
        return new BackgroundDataAccessor.BoundedPage(records, nextCursor);
    }

    /** One CTE/UPDATE statement owns selection, non-blocking row locks, mutation and return. */
    @Transactional
    List<Map<String, Object>> claimBatch(long tenantId,
                                         BackgroundDataAccessor.BatchClaimRequest request) {
        ModelDefinition model = requirePhysicalDynamicModel(request.modelCode());
        ModelMutationGuard.assertMutable(model, "claimed");

        Map<String, Object> exact = resolveScalarMap(model, request.exactFilters(), false);
        Map<String, List<Object>> in = resolveInMap(model, request.inFilters());
        Map<String, Object> notAfter = resolveScalarMap(
                model, request.notAfterFilters(), false);
        Map<String, Object> claimValues = resolveScalarMap(model, request.claimValues(), true);
        List<String> orderColumns = resolveOrderColumns(model, request.orderByFields());

        List<Map<String, Object>> claimed = dynamicDataMapper.atomicBatchClaimReturning(
                model.getTableName(),
                "pid",
                exact,
                in,
                notAfter,
                claimValues,
                orderColumns,
                model.isSoftDelete(),
                request.limit(),
                tenantId,
                MetaContext.getCurrentUserId());
        if (claimed == null) return List.of();
        if (claimed.size() > request.limit()) {
            throw new IllegalStateException("atomic claim exceeded requested limit");
        }
        JsonbFieldHelper.normalizeJsonReadValues(model, claimed);
        return List.copyOf(claimed);
    }

    private ModelDefinition requirePhysicalDynamicModel(String modelCode) {
        ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new MetaServiceException("Model not found: " + modelCode));
        String sourceType = model.getSourceType();
        if ((sourceType != null && !sourceType.isBlank()
                && !PHYSICAL_SOURCE.equals(sourceType.toLowerCase(Locale.ROOT)))
                || "view".equalsIgnoreCase(model.getModelType())) {
            throw new MetaServiceException(
                    "Bounded background access requires a physical model: " + modelCode);
        }
        String tableName = model.getTableName();
        SqlSafetyUtils.validateIdentifier(tableName, "background model table");
        if (!tableName.startsWith(SystemFieldConstants.DYNAMIC_TABLE_PREFIX)) {
            throw new MetaServiceException(
                    "Bounded background access requires an Aura-managed dynamic table: "
                            + modelCode);
        }
        return model;
    }

    private Map<String, Object> resolveScalarMap(ModelDefinition model,
                                                  Map<String, Object> values,
                                                  boolean mutation) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            FieldDefinition field = resolveStoredField(model, entry.getKey());
            if (mutation) assertClaimWritable(model, field);
            String column = physicalColumn(field);
            if (resolved.put(column, toJdbcValue(entry.getValue())) != null) {
                throw new IllegalArgumentException(
                        "Multiple claim fields resolve to column " + column);
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private Map<String, List<Object>> resolveInMap(
            ModelDefinition model, Map<String, List<Object>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, List<Object>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : values.entrySet()) {
            FieldDefinition field = resolveStoredField(model, entry.getKey());
            String column = physicalColumn(field);
            List<Object> candidates = entry.getValue().stream().map(this::toJdbcValue).toList();
            if (resolved.put(column, candidates) != null) {
                throw new IllegalArgumentException(
                        "Multiple claim fields resolve to column " + column);
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private List<String> resolveOrderColumns(ModelDefinition model, Collection<String> fields) {
        if (fields == null || fields.isEmpty()) return List.of();
        List<String> resolved = new ArrayList<>();
        for (String fieldCode : fields) {
            String column = physicalColumn(resolveStoredField(model, fieldCode));
            if (!"pid".equals(column) && !resolved.contains(column)) resolved.add(column);
        }
        return List.copyOf(resolved);
    }

    private FieldDefinition resolveStoredField(ModelDefinition model, String fieldCode) {
        if (SystemFieldConstants.ALL_INFRASTRUCTURE.contains(fieldCode)) {
            return FieldDefinition.builder()
                    .code(fieldCode)
                    .columnName(fieldCode)
                    .dataType("string")
                    .primaryKey("pid".equals(fieldCode))
                    .build();
        }
        if (model.getFields() == null) {
            throw new IllegalArgumentException("Model has no fields: " + model.getCode());
        }
        return model.getFields().stream()
                .filter(field -> fieldCode.equals(field.getCode()))
                .findFirst()
                .filter(field -> !field.isVirtual() && !field.isJsonbVirtual())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown or virtual field '" + fieldCode + "' on model "
                                + model.getCode()));
    }

    private void assertClaimWritable(ModelDefinition model, FieldDefinition field) {
        if (SystemFieldConstants.ALL_INFRASTRUCTURE.contains(field.getCode())
                || field.isPrimaryKey()
                || Boolean.TRUE.equals(field.getImmutable())
                || field.getImmutableWhen() != null) {
            throw new IllegalArgumentException(
                    "Claim cannot mutate protected field: " + field.getCode());
        }
        String dataType = field.getDataType();
        if (JSON.equalsIgnoreCase(dataType) || JSONB.equalsIgnoreCase(dataType)) {
            throw new IllegalArgumentException(
                    "Claim only supports scalar mutations: " + field.getCode());
        }
        FieldWriterGuard.assertFieldsAllowed(model, List.of(field.getCode()));
    }

    private String physicalColumn(FieldDefinition field) {
        String column = field.getColumnName() == null || field.getColumnName().isBlank()
                ? field.getCode() : field.getColumnName();
        SqlSafetyUtils.validateIdentifier(column, "background model column");
        return column;
    }

    private Object toJdbcValue(Object value) {
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof OffsetDateTime offset) return Timestamp.from(offset.toInstant());
        if (value instanceof ZonedDateTime zoned) return Timestamp.from(zoned.toInstant());
        if (value instanceof LocalDateTime local) return Timestamp.valueOf(local);
        return value;
    }

    private static void requireLimit(int limit) {
        if (limit <= 0 || limit > BackgroundDataAccessor.MAX_BOUNDED_BATCH_SIZE) {
            throw new IllegalArgumentException("bounded page limit is outside the host maximum");
        }
    }
}
