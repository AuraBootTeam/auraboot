package com.auraboot.framework.agent.provider;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ToolProvider implementation for DSL-based tools (commands, named queries, list, get-by-id).
 *
 * Discovers tools by reading DSL metadata tables (ab_command_definition, ab_named_query)
 * instead of ab_agent_tool, and executes them via the standard DSL engine services.
 *
 * Tool code conventions:
 * - cmd:{commandCode}  — execute a DSL command
 * - nq:{queryCode}     — execute a named query
 * - list:{modelCode}   — paginated list by model
 * - get:{modelCode}    — get single record by ID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DslToolProvider implements ToolProvider {

    private static final String PREFIX_CMD = "cmd:";
    private static final String PREFIX_NQ = "nq:";
    private static final String PREFIX_LIST = "list:";
    private static final String PREFIX_GET = "get:";

    private final CommandExecutor commandExecutor;
    private final DynamicDataService dynamicDataService;
    private final NamedQueryService namedQueryService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String providerCode() {
        return "dsl";
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && (
                toolCode.startsWith(PREFIX_CMD) || toolCode.startsWith(PREFIX_NQ) ||
                toolCode.startsWith(PREFIX_LIST) || toolCode.startsWith(PREFIX_GET));
    }

    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        if (ctx.getModelHint() == null || ctx.getModelHint().isBlank()) {
            // Too many tools to enumerate without a model hint
            return List.of();
        }

        String modelHint = ctx.getModelHint();
        int maxResults = ctx.getMaxResults() > 0 ? ctx.getMaxResults() : 20;
        List<ToolDefinition> tools = new ArrayList<>();

        // 1. Discover commands for this model
        try {
            String sql = "SELECT code, display_name, description, agent_hint " +
                    "FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND model_code = #{params.modelCode} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "LIMIT #{params.limit}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", ctx.getTenantId(), "modelCode", modelHint, "limit", maxResults));
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String displayName = (String) row.get("display_name");
                String description = row.get("agent_hint") != null
                        ? (String) row.get("agent_hint")
                        : (String) row.get("description");
                tools.add(ToolDefinition.builder()
                        .toolCode(PREFIX_CMD + code)
                        .toolName(displayName != null ? displayName : code)
                        .description(description)
                        .providerCode("dsl")
                        .toolType("dsl_command")
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to discover DSL commands for model {}: {}", modelHint, e.getMessage());
        }

        // 2. Discover named queries matching this model
        try {
            String sql = "SELECT code, title, description, purpose " +
                    "FROM ab_named_query " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code LIKE #{params.codePattern} " +
                    "AND status = 'published' " +
                    "LIMIT #{params.limit}";
            int remaining = maxResults - tools.size();
            if (remaining > 0) {
                List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                        Map.of("tenantId", ctx.getTenantId(), "codePattern", modelHint + "%", "limit", remaining));
                for (Map<String, Object> row : rows) {
                    String code = (String) row.get("code");
                    String title = (String) row.get("title");
                    String description = row.get("purpose") != null
                            ? (String) row.get("purpose")
                            : (String) row.get("description");
                    tools.add(ToolDefinition.builder()
                            .toolCode(PREFIX_NQ + code)
                            .toolName(title != null ? title : code)
                            .description(description)
                            .providerCode("dsl")
                            .toolType("dsl_query")
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover named queries for model {}: {}", modelHint, e.getMessage());
        }

        // 3. Always add generic list + get tools for the model
        if (tools.size() < maxResults) {
            tools.add(ToolDefinition.builder()
                    .toolCode(PREFIX_LIST + modelHint)
                    .toolName("List " + modelHint)
                    .description("Paginated list of " + modelHint + " records. Params: pageNum, pageSize, keyword.")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .build());
        }
        if (tools.size() < maxResults) {
            tools.add(ToolDefinition.builder()
                    .toolCode(PREFIX_GET + modelHint)
                    .toolName("Get " + modelHint)
                    .description("Get a single " + modelHint + " record by ID. Params: recordId (required).")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .build());
        }

        return tools;
    }

    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            if (toolCode.startsWith(PREFIX_CMD)) {
                return executeCommand(toolCode.substring(PREFIX_CMD.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_NQ)) {
                return executeNamedQuery(toolCode.substring(PREFIX_NQ.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_LIST)) {
                return executeList(toolCode.substring(PREFIX_LIST.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_GET)) {
                return executeGetById(toolCode.substring(PREFIX_GET.length()), params, start);
            }

            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("Unknown DSL tool code prefix: " + toolCode)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("DSL tool execution failed: toolCode={}, error={}", toolCode, e.getMessage());
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ========== Execution Methods ==========

    private ProviderExecutionResult executeCommand(String commandCode, Map<String, Object> params, long start) {
        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(params != null ? params : Map.of());
        CommandExecuteResult cmdResult = commandExecutor.execute(commandCode, req);
        return ProviderExecutionResult.builder()
                .success(true)
                .data(cmdResult.getData() != null ? cmdResult.getData() : Map.of())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeNamedQuery(String queryCode, Map<String, Object> params, long start) {
        NamedQueryTestRequest nqReq = new NamedQueryTestRequest();
        nqReq.setParameters(params);
        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqReq);
        List<Map<String, Object>> records = result.getRecords() != null ? result.getRecords() : List.of();
        return ProviderExecutionResult.builder()
                .success(true)
                .data(Map.of("total", result.getTotal(), "records", records))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeList(String modelCode, Map<String, Object> params, long start) {
        DynamicQueryRequest qr = buildQueryRequest(params);
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, qr);
        List<Map<String, Object>> records = result.getRecords() != null ? result.getRecords() : List.of();
        return ProviderExecutionResult.builder()
                .success(true)
                .data(Map.of("total", result.getTotal(), "records", records))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeGetById(String modelCode, Map<String, Object> params, long start) {
        String recordId = params != null ? (String) params.get("recordId") : null;
        if (recordId == null) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("recordId is required for get: tool")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
        Map<String, Object> record = dynamicDataService.getById(modelCode, recordId);
        return ProviderExecutionResult.builder()
                .success(record != null)
                .data(record != null ? Map.of("record", record) : Map.of())
                .errorMessage(record == null ? "Record not found: " + recordId : null)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ========== Helpers ==========

    private DynamicQueryRequest buildQueryRequest(Map<String, Object> params) {
        if (params == null) params = Map.of();
        Integer pageNum = params.get("pageNum") instanceof Number n ? n.intValue() : 1;
        Integer pageSize = params.get("pageSize") instanceof Number n ? n.intValue() : 20;
        String keyword = params.get("keyword") instanceof String s ? s : null;
        return DynamicQueryRequest.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .keyword(keyword)
                .build();
    }
}
