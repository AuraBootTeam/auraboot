package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.nlmodeling.NlModelingService;
import com.auraboot.framework.agent.nlmodeling.dto.NlApplyRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingResponse;
import com.auraboot.framework.agent.service.SubAgentRunner;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.ai.AiModelSuggestionService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Platform-level built-in tool provider.
 *
 * <p>Hosts the three platform tools migrated from {@code ChatToolExecutor}:
 * <ul>
 *   <li>{@code platform.execute_sql}  — execute a read-only SQL SELECT query</li>
 *   <li>{@code platform.list_models}  — list available data models with optional field details</li>
 *   <li>{@code platform.model_suggest} — AI-powered model suggestion from a natural language description</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 3.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformToolProvider implements ToolProvider {

    private static final int MAX_QUERY_ROWS = 100;

    private final com.auraboot.framework.meta.service.ModelReferenceLookupService referenceLookup;
    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;
    private final AiModelSuggestionService aiModelSuggestionService;
    private final NlModelingService nlModelingService;
    private final SubAgentRunner subAgentRunner;
    private final JdbcTemplate jdbcTemplate;
    private final DataPermissionEngine dataPermissionEngine;
    private final com.auraboot.framework.meta.service.SecureSqlRewriter sqlRewriter;

    private static final List<ToolDefinition> PLATFORM_TOOLS = List.of(
        ToolDefinition.builder().toolCode("platform.resolve_reference").toolName("Resolve reference label")
            .description("Resolve an exact business label to its declared stored reference value. Call before filtering a reference by customer name; never infer customer membership from project names. Missing, ambiguous or denied labels fail closed.")
            .providerCode("platform").toolType("platform").sourceCode("platform.resolve_reference")
            .operationKind("query").riskLevel("L0").confirmationPolicy("none")
            .parameterSchema(com.auraboot.framework.meta.service.ModelReferenceLookupService.inputSchema()).build(),
        ToolDefinition.builder()
            .toolCode("platform.execute_sql")
            .toolName("Execute SQL Query")
            .description("Execute a read-only SQL SELECT query with safety validation and tenant isolation. "
                    + "Use this directly when the task supplies the SQL; use platform.list_models first "
                    + "only when table or field names must be discovered.")
            .providerCode("platform")
            .toolType("platform")
            .sourceCode("platform.execute_sql")
            .operationKind("query")
            .riskLevel("L1")
            .confirmationPolicy("none")
            .parameterSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sql", Map.of(
                                    "type", "string",
                                    "description", "The read-only SELECT statement to execute"),
                            "interpretation", Map.of(
                                    "type", "string",
                                    "description", "Optional explanation of the requested analysis"),
                            "chartType", Map.of(
                                    "type", "string",
                                    "description", "Optional result presentation type")),
                    "required", List.of("sql")))
            .build(),
        ToolDefinition.builder()
            .toolCode("platform.list_models")
            .toolName("List Data Models")
            .description("List available data models with optional field details for schema discovery. "
                    + "Use before platform.execute_sql when the task does not provide exact table/field names.")
            .providerCode("platform")
            .toolType("platform")
            .sourceCode("platform.list_models")
            .operationKind("query")
            .riskLevel("L0")
            .confirmationPolicy("none")
            .parameterSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "keyword", Map.of(
                                    "type", "string",
                                    "description", "Optional model-name search keyword"),
                            "includeFields", Map.of(
                                    "type", "boolean",
                                    "description", "Whether to include field definitions"))))
            .build(),
        ToolDefinition.builder()
            .toolCode("platform.model_suggest")
            .toolName("Suggest Data Model")
            .description("AI-powered model suggestion from a natural language description")
            .providerCode("platform")
            .toolType("platform")
            .sourceCode("platform.model_suggest")
            .riskLevel("L1")
            .confirmationPolicy("none")
            .parameterSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "description", Map.of(
                                    "type", "string",
                                    "description", "Natural language description of the model to suggest"),
                            "language", Map.of(
                                    "type", "string",
                                    "description", "Optional response language requested by the user")),
                    "required", List.of("description")))
            .build(),
        ToolDefinition.builder()
            .toolCode("platform.create_model")
            .toolName("Create Data Model")
            .description("Create a complete data model with fields, commands, pages, and menus "
                    + "from a natural language description. This is a WRITE operation that creates "
                    + "real database tables and UI pages. Use platform.model_suggest first to preview, "
                    + "then call this tool to actually create the model.")
            .providerCode("platform")
            .toolType("platform")
            .sourceCode("platform.create_model")
            .riskLevel("L3")
            .confirmationPolicy("approval_required")
            .requiresApproval(true)
            .parameterSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "description", Map.of("type", "string",
                                    "description", "Natural language description of the data model to create")),
                    "required", List.of("description")))
            .build(),
        ToolDefinition.builder()
            .toolCode("platform.delegate_task")
            .toolName("Delegate Subtask to Sub-Agent")
            .description("Spawn a child agent run under the current run to handle a side-task in parallel. "
                    + "The child run inherits the parent's agent (or use agentCode to override) and tenant; "
                    + "the parent does NOT block on the child — completion is observed via "
                    + "ChildRunCompletedEvent. This is a WRITE operation that creates a new ab_agent_run "
                    + "row. Use only when the user's request contains a clearly separable side-task that "
                    + "should run independently of the current intent.")
            .providerCode("platform")
            .toolType("platform")
            .sourceCode("platform.delegate_task")
            .riskLevel("L2")
            .confirmationPolicy("approval_required")
            .requiresApproval(true)
            .parameterSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subtaskMessage", Map.of("type", "string",
                                    "description", "The user-facing description of the subtask the "
                                            + "child agent should perform"),
                            "agentCode", Map.of("type", "string",
                                    "description", "Optional agent_code for the child run. "
                                            + "Defaults to inheriting the parent run's agent.")),
                    "required", List.of("subtaskMessage")))
            .build()
    );

    @Override
    public String providerCode() {
        return "platform";
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && toolCode.startsWith("platform.");
    }

    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        return PLATFORM_TOOLS;
    }

    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = switch (toolCode) {
                case "platform.execute_sql"    -> executeSql(params, tenantId);
                case "platform.list_models"    -> listModels(params);
                case "platform.resolve_reference" -> referenceLookup.resolve(getStringParam(params, "modelCode"),
                        getStringParam(params, "fieldCode"), getStringParam(params, "displayValue"));
                case "platform.model_suggest"  -> modelSuggest(params);
                case "platform.create_model"   -> createModel(params);
                case "platform.fill_form"     -> fillForm(params);
                case "platform.delegate_task" -> delegateTask(params, tenantId);
                default -> Map.of("error", "Unknown platform tool: " + toolCode);
            };
            boolean success = !result.containsKey("error") || Boolean.TRUE.equals(result.get("success"));
            return ProviderExecutionResult.builder()
                    .success(success)
                    .data(result)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("PlatformToolProvider execution failed for {}: {}", toolCode, e.getMessage(), e);
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ==================== platform.execute_sql ====================

    /**
     * Execute an LLM-generated SQL SELECT query with safety validation.
     * Validates SELECT-only, injects tenant isolation, and caps result rows.
     */
    private Map<String, Object> executeSql(Map<String, Object> params, Long tenantId) {
        String sql = getStringParam(params, "sql");
        if (sql == null || sql.isBlank()) {
            return errorResult("sql is required for platform.execute_sql");
        }

        sql = sql.trim().replaceAll(";+$", "");

        // Safety: validate SELECT-only (no DML/DDL)
        try {
            SqlSafetyUtils.validateSelectOnlySql(sql);
        } catch (IllegalArgumentException e) {
            return errorResult("SQL validation failed: " + e.getMessage());
        }

        // Build params with tenantId for #{params.tenantId} placeholders
        Map<String, Object> queryParams = new LinkedHashMap<>();
        Long effectiveTenantId = tenantId != null ? tenantId : MetaContext.getCurrentTenantId();
        if (effectiveTenantId == null || effectiveTenantId <= 0
                || (MetaContext.getCurrentTenantId() != null
                    && !effectiveTenantId.equals(MetaContext.getCurrentTenantId()))) {
            return errorResult("Tenant context is required for SQL execution");
        }
        queryParams.put("tenantId", effectiveTenantId);

        // Apply scope at every physical source before any caller-authored OR, join,
        // aggregate or subquery can evaluate. Parsing failures must never execute raw SQL.
        try {
            sql = sqlRewriter.rewriteForTenant(sql);
        } catch (RuntimeException invalid) {
            return errorResult("SQL source scope could not be established. Use a DSL/list/named-query tool.");
        }
        // An outer bound also caps explicit and nested LIMIT clauses.
        sql = "SELECT * FROM (" + sql + ") AS _tool_result LIMIT " + MAX_QUERY_ROWS;

        log.info("platform.execute_sql SQL: {}", sql);

        List<Map<String, Object>> rows;
        try {
            rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, queryParams);
        } catch (Exception e) {
            log.warn("platform.execute_sql failed: {}", e.getMessage());
            return enrichSqlError(sql, e.getMessage());
        }

        if (rows == null) rows = Collections.emptyList();

        // SEC-005: execute_sql reads mt_* tables via selectByQueryWithoutTenant, which
        // bypasses the row-level ACL + field masking that DynamicDataService applies on
        // the DSL list/get path. Re-apply DataPermissionEngine post-filtering so the LLM
        // SQL tool honours the same data-permission policies as the rest of the platform.
        rows = applyDataPermissions(sql, rows, effectiveTenantId);

        // Extract column names from first row
        List<String> columns = rows.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(rows.get(0).keySet());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("interpretation", getStringParam(params, "interpretation"));
        output.put("chartType", getStringParam(params, "chartType") != null
                ? getStringParam(params, "chartType") : "table");
        output.put("columns", columns);
        output.put("sql", sql);
        output.put("total", rows.size());

        // Truncate rows to keep LLM context manageable
        if (rows.size() > 20) {
            output.put("records", rows.subList(0, 20));
            output.put("truncated", true);
        } else {
            output.put("records", rows);
        }
        output.put("instruction", "Query complete. Present the results to the user in a readable table format.");
        return output;
    }

    // ==================== platform.list_models ====================

    /**
     * List available data models with optional field details.
     * Lets the LLM discover the database schema for SQL generation.
     */
    private Map<String, Object> listModels(Map<String, Object> params) {
        String keyword = getStringParam(params, "keyword");
        boolean includeFields = parseBool(params.get("includeFields"));

        var modelsPage = metaModelService.searchModels(1, 50, keyword, null, null, null, null, null, null, null, true);
        if (modelsPage == null || modelsPage.getRecords() == null) {
            return errorResult("No models found");
        }

        List<Map<String, Object>> models = new ArrayList<>();
        for (var model : modelsPage.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", model.getCode());
            m.put("displayName", model.getDisplayName());
            m.put("tableName", model.getTableName() != null ? model.getTableName() : "mt_" + model.getCode());

            if (includeFields) {
                Optional<ModelDefinition> fullModel = metaModelService.getModelDefinition(model.getCode());
                if (fullModel.isPresent() && fullModel.get().getFields() != null) {
                    List<Map<String, String>> fields = fullModel.get().getFields().stream()
                            .filter(f -> !SystemFieldConstants.isSystemField(f.getCode()))
                            .map(f -> {
                                Map<String, String> fm = new LinkedHashMap<>();
                                fm.put("code", f.getCode());
                                fm.put("column", f.getColumnName() != null ? f.getColumnName() : f.getCode());
                                fm.put("type", f.getDataType());
                                fm.put("label", f.getDisplayName());
                                return fm;
                            })
                            .collect(Collectors.toList());
                    m.put("fields", fields);
                }
            }
            models.add(m);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("total", models.size());
        output.put("models", models);
        output.put("hint", "IMPORTANT: Table names use mt_ prefix. For example, model 'sales_lead' → table 'mt_sales_lead'. "
                + "All mt_* tables have: id, pid, tenant_id, created_at, updated_at, created_by, updated_by. "
                + "ALWAYS include 'WHERE tenant_id = #{params.tenantId}' for data isolation.");
        output.put("instruction", "Schema loaded. Now use platform.execute_sql to query the data. Do NOT call platform.list_models again.");
        return output;
    }

    // ==================== platform.model_suggest ====================

    private Map<String, Object> modelSuggest(Map<String, Object> params) {
        String description = getStringParam(params, "description");
        if (description == null || description.isBlank()) {
            return errorResult("description is required for platform.model_suggest");
        }

        String language = getStringParam(params, "language");

        var suggestion = aiModelSuggestionService.suggestModel(description, language);

        if (suggestion == null) {
            return errorResult("AI model suggestion is not available. "
                    + "Please configure an LLM provider (ai.service.enabled=true) "
                    + "or create the model manually via the Model Manager page. "
                    + "Do NOT retry this tool — it will not work without AI configuration.");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("modelCode", suggestion.getModelCode());
        output.put("modelName", suggestion.getModelName());
        output.put("description", suggestion.getDescription());
        output.put("fields", suggestion.getFields());
        output.put("suggestedViews", suggestion.getSuggestedViews());
        return output;
    }

    // ==================== platform.create_model ====================

    /**
     * Create a complete data model from a natural language description.
     * Generates DSL via NlModelingService, then applies it as a plugin.
     */
    private Map<String, Object> createModel(Map<String, Object> params) {
        String description = getStringParam(params, "description");
        if (description == null || description.isBlank()) {
            return errorResult("description is required for platform.create_model");
        }

        // Step 1: Generate DSL via NL Modeling
        NlModelingRequest genRequest = NlModelingRequest.builder()
                .description(description)
                .options(NlModelingRequest.Options.builder()
                        .generatePages(true)
                        .generateCommands(true)
                        .generateMenus(true)
                        .generateI18n(true)
                        .generateBindings(true)
                        .build())
                .build();

        NlModelingResponse genResponse;
        try {
            genResponse = nlModelingService.generate(genRequest);
        } catch (Exception e) {
            // CATCH: non-transactional LLM call, safe to handle
            log.error("NL Modeling generate failed: {}", e.getMessage(), e);
            return errorResult("Failed to generate model definition: " + e.getMessage());
        }

        if (genResponse == null || genResponse.getResources() == null) {
            return errorResult("NL Modeling returned empty result. AI provider may not be configured.");
        }

        if (genResponse.getValidationErrors() != null && !genResponse.getValidationErrors().isEmpty()) {
            return errorResult("Generated model has validation errors: "
                    + String.join("; ", genResponse.getValidationErrors()));
        }

        // Step 2: Apply as plugin
        String pluginCode = genResponse.getPluginCode();
        if (pluginCode == null || pluginCode.isBlank()) {
            pluginCode = "nlm_" + System.currentTimeMillis();
        }

        NlApplyRequest applyRequest = NlApplyRequest.builder()
                .pluginCode(pluginCode)
                .resources(genResponse.getResources())
                .build();

        try {
            var applyResult = nlModelingService.apply(applyRequest);
            if (applyResult == null || !applyResult.isSuccess()) {
                String err = applyResult != null ? applyResult.getErrorMessage() : "unknown error";
                return errorResult("Failed to apply model: " + err);
            }
        } catch (Exception e) {
            // CATCH: non-transactional plugin import orchestration, safe to handle
            log.error("NL Modeling apply failed: {}", e.getMessage(), e);
            return errorResult("Failed to apply model: " + e.getMessage());
        }

        // Step 3: Return success summary
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("pluginCode", pluginCode);
        output.put("summary", genResponse.getSummary());
        output.put("instruction", "Model created successfully. The user can now find it in the sidebar menu. "
                + "Tell the user the model has been created with all fields, pages, and menus.");

        NlModelingResponse.Resources res = genResponse.getResources();
        output.put("modelCount", res.getModels() != null ? res.getModels().size() : 0);
        output.put("fieldCount", res.getFields() != null ? res.getFields().size() : 0);
        output.put("pageCount", res.getPages() != null ? res.getPages().size() : 0);
        output.put("commandCount", res.getCommands() != null ? res.getCommands().size() : 0);
        return output;
    }

    // ==================== platform.fill_form ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> fillForm(Map<String, Object> params) {
        Object fieldsObj = params.get("fields");
        if (fieldsObj == null || (fieldsObj instanceof Map && ((Map<?, ?>) fieldsObj).isEmpty()
                && (!(params.get("reviews") instanceof Map<?, ?> reviews) || reviews.isEmpty()))) {
            return errorResult("fields is required for platform.fill_form");
        }

        Map<String, Object> fields;
        if (fieldsObj instanceof Map) {
            fields = (Map<String, Object>) fieldsObj;
        } else {
            return errorResult("fields must be a JSON object");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("action", "form_fill");
        output.put("fields", fields);
        output.put("fieldCount", fields.size());
        if (params.get("reviews") instanceof Map<?, ?> reviews) output.put("reviews", reviews);
        output.put("source", getStringParam(params, "source"));
        output.put("confidence", params.get("confidence"));
        output.put("instruction", "Form fill data ready. The frontend will populate the form. "
                + "Tell the user which fields were extracted and ask them to review before submitting.");
        return output;
    }

    // ==================== platform.delegate_task ====================

    /**
     * Spawn a child run under the current run via {@link SubAgentRunner}. The
     * parent run id is read from {@link StepContext#getRunPid()} (set by
     * {@code ToolLoopService.executeToolCall}); throws clear error if absent
     * (delegate_task can only be called from inside a tool loop, never from
     * an ad-hoc invocation). Origin is fixed to {@code "delegate_task"}, which
     * matches the {@code ab_agent_run.subtask_origin} CHECK constraint added
     * by P0-6.
     */
    private Map<String, Object> delegateTask(Map<String, Object> params, Long tenantId) {
        String subtaskMessage = getStringParam(params, "subtaskMessage");
        if (subtaskMessage == null || subtaskMessage.isBlank()) {
            return errorResult("subtaskMessage is required for platform.delegate_task");
        }
        String parentRunPid = com.auraboot.framework.agent.service.StepContext.getRunPid();
        if (parentRunPid == null || parentRunPid.isBlank()) {
            return errorResult("platform.delegate_task can only be invoked from inside an active run "
                    + "(ToolLoopService binds the run pid before each tool call). No parent run on the "
                    + "current thread.");
        }
        Long effectiveTenantId = tenantId != null ? tenantId : MetaContext.getCurrentTenantId();
        if (effectiveTenantId == null || effectiveTenantId <= 0
                || (MetaContext.getCurrentTenantId() != null
                    && !effectiveTenantId.equals(MetaContext.getCurrentTenantId()))) {
            return errorResult("Tenant context is required for platform.delegate_task");
        }

        try {
            SubAgentRunner.SpawnResult spawn = subAgentRunner.spawn(
                    effectiveTenantId,
                    parentRunPid,
                    /* sessionId */ null,
                    subtaskMessage,
                    /* origin */ "delegate_task");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("success", true);
            output.put("childRunPid", spawn.getChildRunPid());
            output.put("childTaskPid", spawn.getChildTaskPid());
            output.put("origin", spawn.getOrigin());
            output.put("message", "Subtask delegated. The child agent run will execute asynchronously; "
                    + "completion is observed via ChildRunCompletedEvent. The parent run does NOT block.");
            return output;
        } catch (CrossTenantAclDeniedException denied) {
            // Q11: tool-layer must surface ACL denials as structured errors,
            // not raw exception strings, so the LLM can reason about the
            // failure (no_grant vs expired vs revoked) and decide whether
            // to retry, ask the user, or abort.
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("error", "cross_tenant_not_granted");
            output.put("parent_tenant", denied.parentTenantId());
            output.put("child_tenant", denied.childTenantId());
            output.put("reason", mapDecisionToReason(denied.decision().code()));
            output.put("decision", denied.decision().code());
            output.put("message", denied.getMessage());
            return output;
        } catch (IllegalStateException e) {
            // SubAgentRunner refuses parent-not-running / parent-missing etc.
            return errorResult("delegate_task refused: " + e.getMessage());
        } catch (Exception e) {
            log.error("platform.delegate_task failed: {}", e.getMessage(), e);
            return errorResult("delegate_task execution failed: " + e.getMessage());
        }
    }

    /**
     * Map an internal decision code to the public {@code reason} field
     * surfaced to the LLM. Keeps the tool error contract stable even if the
     * internal enum gains new codes (the reason set is what callers branch on).
     */
    private static String mapDecisionToReason(String decisionCode) {
        if (decisionCode == null) return "no_grant";
        return switch (decisionCode) {
            case "denied_expired" -> "expired";
            case "denied_revoked" -> "revoked";
            case "denied_feature_disabled" -> "feature_disabled";
            default -> "no_grant";
        };
    }

    // ==================== Helpers ====================

    private static final Pattern COLUMN_NOT_EXIST = Pattern.compile(
            "column \"?([\\w\\.]+)\"? (?:of relation \"?(\\w+)\"? )?does not exist",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_NOT_EXIST = Pattern.compile(
            "relation \"?(\\w+)\"? does not exist",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_TABLE = Pattern.compile(
            "\\bfrom\\s+(mt_\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MT_TABLE_REFERENCE = Pattern.compile("\\bmt_(\\w+)", Pattern.CASE_INSENSITIVE);

    /**
     * Turn opaque SQL errors into actionable guidance for the LLM.
     * On "column does not exist" / "relation does not exist", attach the model's
     * available fields so the LLM can self-correct and retry.
     */
    private Map<String, Object> enrichSqlError(String sql, String rawError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", "Query execution failed: " + rawError);

        if (rawError == null) return result;

        Matcher colMatch = COLUMN_NOT_EXIST.matcher(rawError);
        Matcher tableMatch = TABLE_NOT_EXIST.matcher(rawError);
        String failedColumn = colMatch.find() ? colMatch.group(1) : null;
        String failedTable = tableMatch.find() ? tableMatch.group(1) : null;

        // Fall back to parsing FROM clause to locate the model
        String targetTable = failedTable;
        if (targetTable == null) {
            Matcher fromMatch = FROM_TABLE.matcher(sql == null ? "" : sql);
            if (fromMatch.find()) targetTable = fromMatch.group(1);
        }

        if (targetTable != null && targetTable.startsWith("mt_")) {
            String modelCode = targetTable.substring(3);
            try {
                Optional<ModelDefinition> def = metaModelService.getModelDefinition(modelCode);
                if (def.isPresent() && def.get().getFields() != null) {
                    List<Map<String, String>> fields = def.get().getFields().stream()
                            .filter(f -> !SystemFieldConstants.isSystemField(f.getCode()))
                            .map(f -> {
                                Map<String, String> fm = new LinkedHashMap<>();
                                fm.put("code", f.getCode());
                                fm.put("column", f.getColumnName() != null ? f.getColumnName() : f.getCode());
                                fm.put("label", f.getDisplayName());
                                return fm;
                            })
                            .collect(Collectors.toList());
                    result.put("modelCode", modelCode);
                    result.put("table", targetTable);
                    result.put("availableFields", fields);
                    if (failedColumn != null) {
                        result.put("failedColumn", failedColumn);
                        result.put("recovery",
                                "The column '" + failedColumn + "' does not exist in table '" + targetTable
                                        + "'. Review 'availableFields' above, pick the closest match by label/code"
                                        + " (e.g. Chinese '行业' may map to 'industry_type', 'trade', 'category',"
                                        + " or be absent entirely), then retry platform.execute_sql ONCE with the"
                                        + " corrected column. If no field is semantically close, tell the user"
                                        + " the data is not captured and suggest alternative dimensions from"
                                        + " availableFields.");
                    } else {
                        result.put("recovery",
                                "Review 'availableFields' and retry with a valid column name.");
                    }
                    return result;
                }
            } catch (Exception lookupErr) {
                log.debug("enrichSqlError lookup failed for {}: {}", modelCode, lookupErr.getMessage());
            }
        }

        if (failedTable != null) {
            result.put("recovery",
                    "Table '" + failedTable + "' does not exist. Call platform.list_models"
                            + " with includeFields=true to discover valid tables. Remember the 'mt_' prefix.");
        }
        return result;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message != null ? message : "Unknown error");
        return result;
    }

    private static String getStringParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    /** Accept Boolean, "true"/"false" strings, or 1/0 numbers (LLMs often stringify). */
    private static boolean parseBool(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return "true".equalsIgnoreCase(val.toString().trim());
    }

    /**
     * SEC-005: apply the same row-level ACL + field masking that
     * {@code DynamicDataService} applies on the DSL list/get path.
     *
     * <p>Post-query filtering can only be attributed to a single model, so it is
     * applied when the query targets exactly one {@code mt_<model>} table. Cross-model
     * joins / aggregates / non-mt_ system tables cannot be mapped to one model's row
     * policies; those rely on the physical-source tenant scopes as the isolation boundary. Any failure inside the
     * engine propagates to {@link #execute} and is surfaced as a failed result
     * (fail-secure: no rows leak).
     */
    private List<Map<String, Object>> applyDataPermissions(String sql,
                                                           List<Map<String, Object>> rows,
                                                           Long tenantId) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        String modelCode = singleTargetModelCode(sql);
        if (modelCode == null) {
            // Cannot attribute per-row ACL to one model; tenant predicate already enforced.
            return rows;
        }
        Long userId = ExecutionPrincipalContext.current()
                .map(principal -> principal.actorUserId())
                .orElseGet(MetaContext::getCurrentUserId);

        // Row-level ACL (SELF / DEPARTMENT / PROJECT / CUSTOM) — narrows to accessible rows.
        List<Map<String, Object>> filtered =
                dataPermissionEngine.filterRecords(tenantId, modelCode, userId, rows);

        // Column-level masking.
        List<FieldMaskRule> maskRules =
                dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
        if (maskRules != null && !maskRules.isEmpty()) {
            filtered = dataPermissionEngine.applyFieldMasking(filtered, maskRules);
        }
        return filtered;
    }

    /**
     * Return the single {@code mt_<model>} model code referenced by the SQL, or
     * {@code null} when zero or more than one distinct {@code mt_} table is present
     * (per-row ACL cannot be attributed to a single model in those cases).
     */
    private static String singleTargetModelCode(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = MT_TABLE_REFERENCE.matcher(sql);
        String first = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (first == null) {
                first = candidate;
            } else if (!first.equalsIgnoreCase(candidate)) {
                return null;
            }
        }
        return first;
    }

}
