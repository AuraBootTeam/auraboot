package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticLineageResponse;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.parser.SemanticValidator;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import com.auraboot.framework.semantic.service.SemanticCatalogService;
import com.auraboot.framework.semantic.service.SemanticLineageService;
import com.auraboot.framework.semantic.service.SemanticPublishService;
import com.auraboot.framework.semantic.service.SemanticQueryService;
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
 * <p>Tenant scoping uses {@link MetaContext}. Per-metric permissions are
 * enforced inside {@link com.auraboot.framework.semantic.compiler.MetricCompiler}
 * (via metric.requiredPermissions) and RLS injection
 * (via {@link com.auraboot.framework.semantic.compiler.AccessPolicyCompiler}).
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
    private final SemanticValidator validator;

    @PostMapping("/query")
    public SemanticQueryResponse query(@RequestBody SemanticQueryRequest request) {
        return queryService.executeQuery(request, currentUser());
    }

    @PostMapping("/sql")
    public SemanticQueryResponse explain(@RequestBody SemanticQueryRequest request) {
        return queryService.explainQuery(request, currentUser());
    }

    /**
     * Parse + validate a YAML body without persisting. Use during authoring.
     * Returns {@code 200 OK + model summary} on success, {@code 400} on either
     * SchemaInvalid or ValidationException.
     */
    @PostMapping(value = "/validate", consumes = {"application/yaml", "text/yaml", "text/plain"})
    public Map<String, Object> validate(@RequestBody byte[] yamlBytes) {
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
        return out;
    }

    /**
     * Publish (or upsert) a YAML to ab_semantic_*. Returns the model pid.
     * Separate from {@code /validate} so authors can iterate without DB writes.
     */
    @PostMapping(value = "/publish",
            consumes = {"application/yaml", "text/yaml", "text/plain"})
    public Map<String, Object> publish(@RequestBody byte[] yamlBytes,
                                        @RequestParam(name = "pluginCode") String pluginCode) {
        UserContext user = currentUser();
        String pid = publishService.publishFromYaml(yamlBytes, pluginCode,
                user.tenantId(), user.userId());
        return Map.of("ok", true, "pid", pid);
    }

    @GetMapping("/meta")
    public SemanticMetaResponse meta() {
        return catalogService.listCatalog(currentTenantId());
    }

    @GetMapping("/lineage/{pid}")
    public SemanticLineageResponse lineage(@PathVariable("pid") String pid) {
        return lineageService.describe(currentTenantId(), pid);
    }

    // -- helpers -------------------------------------------------------------

    private Long currentTenantId() {
        return MetaContext.get().getTenantId();
    }

    private UserContext currentUser() {
        MetaContext ctx = MetaContext.get();
        // v0.1 user_attributes are not yet loaded from a persistent store; pass
        // an empty map. RLS access policies that depend on user_attributes will
        // throw USER_ATTRIBUTE_MISSING — which is correct behavior until W4
        // wiring of attributes is completed.
        return new UserContext(ctx.getUserId(), ctx.getTenantId(),
                Collections.emptyMap());
    }
}
