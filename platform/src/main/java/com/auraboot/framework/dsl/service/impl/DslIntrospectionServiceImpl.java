package com.auraboot.framework.dsl.service.impl;

import com.auraboot.framework.dsl.dto.DslIntrospectionResponse;
import com.auraboot.framework.dsl.dto.DslIntrospectionResponse.*;
import com.auraboot.framework.dsl.service.DslIntrospectionService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.registry.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the DSL Schema Introspection Protocol.
 * <p>
 * Aggregates metadata from all DSL tables (models, fields, commands, pages)
 * and open extension registries into a unified introspection response.
 * </p>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DslIntrospectionServiceImpl implements DslIntrospectionService {

    private static final String PROTOCOL_VERSION = "1.0";

    private final MetaModelMapper modelMapper;
    private final MetaFieldMapper fieldMapper;
    private final MetaModelFieldBindingMapper bindingMapper;
    private final CommandDefinitionMapper commandMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;

    // Open extension registries
    private final RenderComponentRegistry renderComponentRegistry;
    private final ExpressionFunctionRegistry expressionFunctionRegistry;
    private final SideEffectHandlerRegistry sideEffectHandlerRegistry;
    private final AutomationActionRegistry automationActionRegistry;
    private final BlockRendererRegistry blockRendererRegistry;

    @Override
    public DslIntrospectionResponse getFullSchema(Set<String> scopes) {
        boolean includeAll = scopes == null || scopes.isEmpty();
        boolean includeModels = includeAll || scopes.contains("models");
        boolean includeCapabilities = includeAll || scopes.contains("capabilities");

        List<Model> allModels = modelMapper.findCurrentByTenant();
        List<ModelIntrospection> modelIntrospections = includeModels
                ? allModels.stream().map(this::buildModelIntrospection).toList()
                : null;

        CapabilityCatalog capabilities = includeCapabilities
                ? getAvailableCapabilities()
                : null;

        // Compute stats
        int modelCount = allModels.size();
        int fieldCount = 0;
        int commandCount = 0;
        int pageCount = 0;
        if (modelIntrospections != null) {
            for (ModelIntrospection mi : modelIntrospections) {
                fieldCount += mi.getFields() != null ? mi.getFields().size() : 0;
                commandCount += mi.getCommands() != null ? mi.getCommands().size() : 0;
                pageCount += mi.getPages() != null ? mi.getPages().size() : 0;
            }
        }

        return DslIntrospectionResponse.builder()
                .version(PROTOCOL_VERSION)
                .exportedAt(Instant.now().toString())
                .tenantId(MetaContext.getCurrentTenantId())
                .stats(IntrospectionStats.builder()
                        .modelCount(modelCount)
                        .fieldCount(fieldCount)
                        .commandCount(commandCount)
                        .pageCount(pageCount)
                        .build())
                .models(modelIntrospections)
                .capabilities(capabilities)
                .build();
    }

    @Override
    public ModelIntrospection getModelSchema(String modelCode) {
        Model model = modelMapper.findCurrentByCode(modelCode);
        if (model == null) {
            return null;
        }
        return buildModelIntrospection(model);
    }

    @Override
    public CapabilityCatalog getAvailableCapabilities() {
        return CapabilityCatalog.builder()
                .dataTypes(enumCodes(DslRegistry.DataType.class))
                .blockTypes(enumCodes(DslRegistry.BlockType.class))
                .commandTypes(enumCodes(DslRegistry.CommandType.class))
                .renderComponents(registryKeys(renderComponentRegistry))
                .expressionFunctions(registryKeys(expressionFunctionRegistry))
                .sideEffectHandlers(registryKeys(sideEffectHandlerRegistry))
                .automationActions(registryKeys(automationActionRegistry))
                .build();
    }

    // ==================== Private helpers ====================

    private ModelIntrospection buildModelIntrospection(Model model) {
        List<FieldIntrospection> fields = buildFieldIntrospections(model.getId());
        List<CommandIntrospection> commands = buildCommandIntrospections(model.getCode());
        List<PageIntrospection> pages = buildPageIntrospections(model.getCode());

        return ModelIntrospection.builder()
                .code(model.getCode())
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .modelCategory(model.getEffectiveModelCategory())
                .modelType(model.getModelType())
                .tableName(model.getTableName())
                .status(model.getStatus())
                .version(model.getVersion())
                .fields(fields)
                .commands(commands)
                .pages(pages)
                .build();
    }

    private List<FieldIntrospection> buildFieldIntrospections(Long modelId) {
        List<ModelFieldBinding> bindings = bindingMapper.findByModelId(modelId);
        if (bindings == null || bindings.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .toList();

        // Batch-load fields
        List<Field> fields = fieldMapper.selectBatchIds(fieldIds);
        Map<Long, Field> fieldMap = fields.stream()
                .collect(Collectors.toMap(Field::getId, f -> f, (a, b) -> a));

        // Build ordered field introspections
        List<FieldIntrospection> result = new ArrayList<>();
        for (ModelFieldBinding binding : bindings) {
            Field field = fieldMap.get(binding.getFieldId());
            if (field == null) continue;

            result.add(FieldIntrospection.builder()
                    .code(field.getCode())
                    .dataType(field.getDataType())
                    .required(binding.getRequired())
                    .searchable(binding.getSearchable())
                    .sortOrder(binding.getFieldOrder())
                    .feature(beanToMap(field.getFeature()))
                    .refTarget(beanToMap(field.getRefTarget()))
                    .uiSchema(beanToMap(field.getUiSchema()))
                    .build());
        }
        return result;
    }

    private List<CommandIntrospection> buildCommandIntrospections(String modelCode) {
        List<CommandDefinition> commands = commandMapper.findByModelCode(modelCode);
        if (commands == null || commands.isEmpty()) {
            return Collections.emptyList();
        }
        return commands.stream().map(cmd -> CommandIntrospection.builder()
                .code(cmd.getCode())
                .displayName(cmd.getDisplayName())
                .description(cmd.getDescription())
                .modelCode(cmd.getModelCode())
                .cmdRiskLevel(cmd.getCmdRiskLevel())
                .agentHint(cmd.getAgentHint())
                .status(cmd.getStatus())
                .inputSchema(parseJsonSafe(cmd.getInputSchema()))
                .executionConfig(parseJsonSafe(cmd.getExecutionConfig()))
                .build()
        ).toList();
    }

    private List<PageIntrospection> buildPageIntrospections(String modelCode) {
        // Note: @TableLogic on deletedFlag auto-appends deleted_flag=false
        LambdaQueryWrapper<PageSchema> wrapper = new LambdaQueryWrapper<PageSchema>()
                .eq(PageSchema::getModelCode, modelCode)
                .orderByAsc(PageSchema::getSortWeight);
        List<PageSchema> pages = pageSchemaMapper.selectList(wrapper);
        if (pages == null || pages.isEmpty()) {
            return Collections.emptyList();
        }
        return pages.stream().map(p -> (PageIntrospection) PageIntrospection.builder()
                .pageKey(p.getPageKey())
                .name(p.getName())
                .kind(p.getKind())
                .profile(p.getProfile())
                .modelCode(p.getModelCode())
                .status(p.getStatus())
                .schemaVersion(p.getSchemaVersion())
                .build()
        ).toList();
    }

    /**
     * Extract enum codes as a sorted list.
     */
    private <E extends Enum<E> & DslRegistry.DslEnum> List<String> enumCodes(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .map(DslRegistry.DslEnum::code)
                .sorted()
                .toList();
    }

    /**
     * Extract keys from RenderComponentRegistry.
     */
    private List<String> registryKeys(RenderComponentRegistry registry) {
        return registry.getAll().stream()
                .map(RenderComponentRegistry.ComponentMeta::code)
                .sorted()
                .toList();
    }

    /**
     * Extract function names from ExpressionFunctionRegistry.
     */
    private List<String> registryKeys(ExpressionFunctionRegistry registry) {
        return registry.getAll().stream()
                .map(ExpressionFunctionRegistry.FunctionMeta::name)
                .sorted()
                .toList();
    }

    /**
     * Extract handler codes from SideEffectHandlerRegistry.
     */
    private List<String> registryKeys(SideEffectHandlerRegistry registry) {
        return registry.getAll().stream()
                .map(SideEffectHandlerRegistry.HandlerMeta::code)
                .sorted()
                .toList();
    }

    /**
     * Extract action codes from AutomationActionRegistry.
     */
    private List<String> registryKeys(AutomationActionRegistry registry) {
        return registry.getAll().stream()
                .map(AutomationActionRegistry.ActionMeta::code)
                .sorted()
                .toList();
    }

    /**
     * Convert a bean to Map safely.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> beanToMap(Object bean) {
        if (bean == null) return null;
        try {
            return objectMapper.convertValue(bean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to convert bean to map: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse a JSONB string to a generic object (Map or List).
     */
    private Object parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON: {}", e.getMessage());
            return json;
        }
    }
}
