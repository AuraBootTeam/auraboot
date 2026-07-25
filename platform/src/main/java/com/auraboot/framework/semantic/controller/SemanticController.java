package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticLineageResponse;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.parser.SemanticYamlValidator;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.auraboot.framework.semantic.service.SemanticCatalogService;
import com.auraboot.framework.semantic.service.SemanticLineageService;
import com.auraboot.framework.semantic.service.SemanticPublishService;
import com.auraboot.framework.semantic.service.SemanticQueryService;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * REST surface for the semantic layer (PRD 16 §6).
 *
 * <p>5 endpoints under {@code /api/semantic}:
 * <ul>
 *   <li>{@code POST /query}    — execute compiled SQL, return rows</li>
 *   <li>{@code POST /sql}      — compile only, return SQL + params (debug)</li>
 *   <li>{@code POST /validate} — parse + validate yaml without persisting</li>
 *   <li>{@code GET  /meta}     — list ACTIVE models + metrics + dimensions</li>
 *   <li>{@code GET  /lineage/{pid}} — incoming + outgoing edges of a node</li>
 * </ul>
 *
 * <p>Authorization: every endpoint requires an explicit permission —
 * {@code query/sql/validate/meta/lineage} need {@link MetaPermission#META_SEMANTIC_USE};
 * {@code publish} needs the stricter {@link MetaPermission#META_SEMANTIC_PUBLISH}.
 * (Without an annotation the PermissionInterceptor fail-opens, so the guard is
 * mandatory, not decorative.)
 *
 * <p>Tenant scoping uses {@link MetaContext}. Per-metric permissions (a metric's
 * {@code required_permissions}) are enforced in
 * {@link com.auraboot.framework.semantic.service.SemanticQueryService} against the
 * caller before execution, and RLS is injected by
 * {@link com.auraboot.framework.semantic.compiler.AccessPolicyCompiler}.
 *
 * <p>All responses are wrapped in the platform {@link ApiResponse} envelope so
 * the web client's {@code ResultHelper.isSuccess} contract holds.
 */
@Slf4j
@RestController
@RequestMapping("/api/semantic")
@RequiredArgsConstructor
public class SemanticController {

    private final SemanticQueryService queryService;
    private final SemanticCatalogService catalogService;
    private final SemanticLineageService lineageService;
    private final SemanticPublishService publishService;
    private final SemanticYamlParser parser;
    private final SemanticYamlValidator validator;
    private final UserAttributeService userAttributeService;

    @PostMapping("/query")
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<SemanticQueryResponse> query(@RequestBody SemanticQueryRequest request) {
        return ApiResponse.success(queryService.executeQuery(request, currentUser()));
    }

    @PostMapping("/sql")
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<SemanticQueryResponse> explain(@RequestBody SemanticQueryRequest request) {
        return ApiResponse.success(queryService.explainQuery(request, currentUser()));
    }

    /**
     * Parse + validate a YAML body without persisting. Use during authoring.
     * Returns {@code 200 OK + model summary} on success, {@code 400} on either
     * SchemaInvalid or ValidationException.
     */
    @PostMapping(value = "/validate", consumes = {"application/yaml", "text/yaml", "text/plain"})
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<Map<String, Object>> validate(@RequestBody byte[] yamlBytes) {
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8);
        SemanticModelDTO dto = parser.parse(yaml);
        validator.validate(dto);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("modelCode", dto.getSemanticModel().getCode());
        out.put("version", dto.getVersion());
        out.put("metricCount", dto.getMetrics().size());
        out.put("dimensionCount", dto.getDimensions().size());
        out.put("entityCount", dto.getEntities().size());
        out.put("accessPolicyCount",
                dto.getAccessPolicies() == null ? 0 : dto.getAccessPolicies().size());
        return ApiResponse.success(out);
    }

    /**
     * Publish (or upsert) a YAML to ab_semantic_*. Returns the model pid.
     * Separate from {@code /validate} so authors can iterate without DB writes.
     */
    @PostMapping(value = "/publish",
            consumes = {"application/yaml", "text/yaml", "text/plain"})
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<Map<String, Object>> publish(@RequestBody byte[] yamlBytes,
                                        @RequestParam(name = "pluginCode") String pluginCode) {
        UserContext user = currentUser();
        String pid = publishService.publishFromYaml(yamlBytes, pluginCode,
                user.tenantId(), user.userId());
        return ApiResponse.success(Map.of("ok", true, "pid", pid));
    }

    @GetMapping("/meta")
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<SemanticMetaResponse> meta() {
        return ApiResponse.success(catalogService.listCatalog(currentTenantId()));
    }

    @GetMapping("/lineage/{pid}")
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<SemanticLineageResponse> lineage(@PathVariable("pid") String pid) {
        return ApiResponse.success(lineageService.describe(currentTenantId(), pid));
    }

    // -- helpers -------------------------------------------------------------

    private Long currentTenantId() {
        return MetaContext.get().getTenantId();
    }

    private UserContext currentUser() {
        MetaContext ctx = MetaContext.get();
        // B.3.1 — load user attributes from ab_user_attribute for RLS evaluation.
        // Empty map if user has no attributes; access policies depending on
        // unresolved keys still throw USER_ATTRIBUTE_MISSING (correct per PRD).
        return new UserContext(ctx.getUserId(), ctx.getTenantId(),
                userAttributeService.getAttributes(ctx.getTenantId(), ctx.getUserId()));
    }
}
