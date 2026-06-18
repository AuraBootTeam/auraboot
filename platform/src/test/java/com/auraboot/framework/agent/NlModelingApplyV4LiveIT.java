package com.auraboot.framework.agent;

import com.auraboot.framework.agent.nlmodeling.NlModelingService;
import com.auraboot.framework.agent.nlmodeling.dto.NlApplyRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live end-to-end closure for AuraQR M3 (pillar ②) Prompt-to-App, per backlog
 * {@code 2026-06-18-prompt-to-app-v4-prompt-and-page-normalizer.md} §"LLM-key block point".
 *
 * <p>The unit-tested deterministic core ({@code normalizePageToV4} + provenance) proves the V2→V4
 * normalizer in isolation, but the <strong>residual DID-NOT-RUN</strong> is the live chain:
 * real NL description → {@link NlModelingService#generate} (real LLM) → {@link NlModelingService#apply}
 * → {@code PluginImportService.executeFromManifest} → the strict v4 page import validator
 * ({@code PageSchemaImportGate} / {@code PageSchemaValidator}). That gate <em>throws</em> on a
 * non-v4 page (schemaVersion≠4 → S-PAGE-VERSION, layout flex → S-PAGE-LAYOUT-TYPE, nested
 * {@code areas.<region>.blocks[]} → S-PAGE-BLOCKS), so {@code apply()} only returns
 * {@code success=true} when every LLM-generated page passed the v4 gate.
 *
 * <p>This IT closes that gap: it asserts the live apply succeeds and that ≥2 LLM-authored pages
 * were imported through the v4 gate — i.e. the prompt rewrite (#4) + normalizer safety net (#1)
 * actually make an LLM page production-importable.
 *
 * <p>Opt-in (decoupled from every-commit CI — L3 live eval): {@code @Tag("agent-eval-live")} +
 * {@code DEEPSEEK_API_KEY}. Without the key the test self-skips (no faked pass).
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live: NL → generate → apply → v4 page import validator success (Prompt-to-App M3)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class NlModelingApplyV4LiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String PLUGIN_CODE = "nl_live_v4_inspection";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private NlModelingService nlModelingService;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live Prompt-to-App apply verification");
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);
        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (prompt-to-app apply live)\""
                + "}";
        CloudConfigSaveRequest req = new CloudConfigSaveRequest();
        req.setConfigLevel("tenant");
        req.setServiceType("llm");
        req.setProviderCode(PROVIDER);
        req.setConfig(configJson);
        req.setEnabled(true);
        req.setPriority(0);
        cloudConfigService.saveConfig(req);
    }

    @AfterAll
    void cleanup() {
        // Scrub the key-bearing cloud_config row (per "rotate/scrub after live" discipline).
        if (tenantId != null) {
            jdbcTemplate.update(DELETE_SEED, tenantId);
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("LLM-generated DSL pages pass the strict v4 import gate end-to-end")
    void nlGeneratedPagesPassV4ImportGate() {
        // A description that asks for list / form / detail pages — exercises page generation so the
        // v4 page gate is actually traversed by ≥2 LLM-authored pages.
        NlModelingRequest request = NlModelingRequest.builder()
                .description("建一个「设备点检」对象,字段包括:设备编号(文本)、点检人(引用员工)、"
                        + "点检时间(日期时间)、点检结果(枚举:待检/正常/异常)、备注(多行文本)。"
                        + "需要新增和编辑命令,以及列表/表单/详情页面。")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        // 1. Live generate (real DeepSeek).
        NlModelingResponse resp = nlModelingService.generate(request);
        assertNotNull(resp, "generate() must return a response");
        NlModelingResponse.Resources res = resp.getResources();
        assertNotNull(res, "generate() must return resources");
        int genPages = res.getPages() == null ? 0 : res.getPages().size();
        int genModels = res.getModels() == null ? 0 : res.getModels().size();
        List<String> genErrors = resp.getValidationErrors() == null ? List.of() : resp.getValidationErrors();
        log.warn("[apply-v4-live] generate: models={} pages={} serviceValidationErrors={} {}",
                genModels, genPages, genErrors.size(), genErrors.isEmpty() ? "" : genErrors);
        assertTrue(genModels >= 1, "generate must produce at least one model");
        assertTrue(genPages >= 2, "generate must produce >=2 pages to exercise the v4 page gate, got " + genPages);

        // 2. Live apply → buildPluginManifestJson → executeFromManifest → v4 PageSchemaImportGate.
        NlApplyRequest applyReq = NlApplyRequest.builder()
                .pluginCode(PLUGIN_CODE)
                .resources(res)
                .build();
        ImportExecuteResult result = nlModelingService.apply(applyReq);
        assertNotNull(result, "apply() must return a result");

        Map<String, Map<String, Integer>> counts = result.getResourceCounts();
        int pagesImported = 0;
        if (counts != null && counts.get("PAGE") != null) {
            pagesImported = counts.get("PAGE").values().stream().mapToInt(Integer::intValue).sum();
        }
        log.warn("[apply-v4-live] apply: success={} status={} pagesImported={} resourceCounts={} error={}",
                result.isSuccess(), result.getStatus(), pagesImported, counts, result.getErrorMessage());

        // 3. Core proof: apply succeeds (the v4 PageSchemaImportGate throws → success=false on any
        //    non-v4 page), and ≥2 LLM-authored pages traversed the gate.
        assertTrue(result.isSuccess(),
                "apply must succeed — proves every LLM-generated page passed the v4 import gate; error="
                        + result.getErrorMessage());
        assertTrue(pagesImported >= 2,
                "expected >=2 pages imported through the v4 gate, got " + pagesImported);
    }
}
