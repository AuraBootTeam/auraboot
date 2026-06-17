package com.auraboot.framework.agent;

import com.auraboot.framework.agent.nlmodeling.NlModelingService;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingRequest;
import com.auraboot.framework.agent.nlmodeling.dto.NlModelingResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-LLM <strong>baseline</strong> quality for the developer-capability "NL → model/DSL"
 * path ({@link NlModelingService#generate}), per architecture finding
 * {@code 2026-06-17-dev-capability-schema-constrained-skill-generation.md} §7.
 *
 * <p>Measures the <em>current</em> path — free-form JSON guided by a schema-aware system
 * prompt + post-hoc validation (the model is NOT hard-constrained by native tool-use). Two
 * cases: a <em>clean</em> task where the user names the types, and a <em>hard</em> task
 * where the model must <strong>infer</strong> types with no hints. The numbers are the
 * baseline the schema-constrained rewrite must beat; any invalid {@code dataType}, missing
 * ENUM {@code dictCode}, or non-empty {@code validationErrors} is the free-form weakness.
 *
 * <p>Opt-in: {@code @Tag("agent-eval-live")} + {@code DEEPSEEK_API_KEY}.
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live baseline: NL→model/DSL generation quality (free-form path) vs real LLM")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class NlModelingLiveQualityIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    /** The dataType enum the system prompt tells the model to use. */
    private static final Set<String> VALID_TYPES = Set.of(
            "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
            "ENUM", "REFERENCE", "FILE", "TEXT");

    @Autowired private NlModelingService nlModelingService;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    /** Expected field: a keyword in displayName/code, the acceptable dataType(s), and whether ENUM needs a dict. */
    private record Expect(String keyword, Set<String> okTypes, boolean needsDict) {
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping NL-modeling baseline measurement");
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);
        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (nl-modeling baseline)\""
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
        if (tenantId != null) {
            jdbcTemplate.update(DELETE_SEED, tenantId);
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("clean task (types named by user)")
    void nlModelingCleanTask() {
        // The user names each type explicitly — the easy case.
        Result r = runAndScore("CLEAN",
                "建一个「设备点检」对象,字段包括:设备编号(文本)、点检人(引用员工)、"
                        + "点检时间(日期时间)、点检结果(枚举:待检/正常/异常)、备注(多行文本)。"
                        + "需要新增和编辑命令,以及列表/表单/详情页面。",
                List.of(
                        new Expect("编号", Set.of("STRING"), false),
                        new Expect("点检人", Set.of("REFERENCE"), false),
                        new Expect("时间", Set.of("DATETIME"), false),
                        new Expect("结果", Set.of("ENUM"), true),
                        new Expect("备注", Set.of("TEXT", "STRING"), false)));
        assertFloors(r, 5);
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("hard task (model must INFER types, no hints)")
    void nlModelingHardTypeInference() {
        // No type hints — the model must infer 金额→DECIMAL, 附件→FILE, 状态→ENUM, 日期→DATE.
        Result r = runAndScore("HARD-INFER",
                "建一个「采购合同」对象,要有:合同编号、甲方、乙方、合同金额、签订日期、"
                        + "到期日期、合同状态、合同附件、备注。生成新增/编辑命令和列表/表单/详情页。",
                List.of(
                        new Expect("编号", Set.of("STRING"), false),
                        new Expect("甲方", Set.of("STRING", "REFERENCE"), false),
                        new Expect("乙方", Set.of("STRING", "REFERENCE"), false),
                        new Expect("金额", Set.of("DECIMAL"), false),
                        new Expect("签订", Set.of("DATE", "DATETIME"), false),
                        new Expect("到期", Set.of("DATE", "DATETIME"), false),
                        new Expect("状态", Set.of("ENUM"), true),
                        new Expect("附件", Set.of("FILE"), false),
                        new Expect("备注", Set.of("TEXT", "STRING"), false)));
        // Lenient floor on the hard case — the report is the signal; we only fail on invalid DSL.
        assertTrue(!r.models.isEmpty(), "must generate a model");
        assertTrue(r.invalidTypes == 0, "generated " + r.invalidTypes + " invalid dataType(s)");
    }

    // ---- core --------------------------------------------------------------

    private record Result(int fieldsGenerated, int typeMatched, int total, int dictOk, int dictExpected,
                          long invalidTypes, List<Map<String, Object>> models, int commands, int pages,
                          int validationErrors) {
    }

    private Result runAndScore(String label, String description, List<Expect> expects) {
        NlModelingRequest request = NlModelingRequest.builder()
                .description(description)
                .options(NlModelingRequest.Options.builder().build())
                .build();
        NlModelingResponse resp = nlModelingService.generate(request);
        assertTrue(resp != null, "generate() must return a response");
        NlModelingResponse.Resources r = resp.getResources();

        List<Map<String, Object>> fields = listOf(r == null ? null : r.getFields());
        List<Map<String, Object>> models = listOf(r == null ? null : r.getModels());
        List<Map<String, Object>> commands = listOf(r == null ? null : r.getCommands());
        List<Map<String, Object>> pages = listOf(r == null ? null : r.getPages());
        List<String> validationErrors = resp.getValidationErrors() == null ? List.of() : resp.getValidationErrors();

        int typeMatched = 0, dictOk = 0, dictExpected = 0;
        StringBuilder rows = new StringBuilder();
        for (Expect e : expects) {
            Map<String, Object> f = findField(fields, e.keyword());
            String dt = f == null ? null : str(f.get("dataType"));
            boolean typeOk = dt != null && e.okTypes().contains(dt.toUpperCase());
            if (typeOk) typeMatched++;
            String dictNote = "";
            if (e.needsDict()) {
                dictExpected++;
                boolean hasDict = f != null && nonEmpty(f.get("dictCode"));
                if (hasDict) dictOk++;
                dictNote = " dictCode=" + (hasDict ? "Y" : "N(MISSING)");
            }
            rows.append(String.format("  field[%s] -> %-22s dataType=%-10s expect=%-22s match=%s%s%n",
                    e.keyword(), f == null ? "(NOT GENERATED)" : str(f.get("code")),
                    dt == null ? "-" : dt, e.okTypes(), typeOk ? "Y" : "N", dictNote));
        }
        long invalidTypes = fields.stream().map(f -> str(f.get("dataType")))
                .filter(d -> d != null && !VALID_TYPES.contains(d.toUpperCase())).count();

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n========== NL→MODEL/DSL FREE-FORM BASELINE [%s] (DeepSeek, single sample) ==========%n", label));
        report.append(rows);
        report.append("  ------------------------------------------------------------------------------------------\n");
        report.append(String.format("  fieldsGenerated=%d  typeCorrect=%d/%d  enumDictOk=%d/%d  invalidDataTypes=%d%n",
                fields.size(), typeMatched, expects.size(), dictOk, dictExpected, invalidTypes));
        report.append(String.format("  models=%d  commands=%d  pages=%d  serviceValidationErrors=%d %s%n",
                models.size(), commands.size(), pages.size(), validationErrors.size(),
                validationErrors.isEmpty() ? "" : validationErrors));
        report.append("==========================================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        return new Result(fields.size(), typeMatched, expects.size(), dictOk, dictExpected, invalidTypes,
                models, commands.size(), pages.size(), validationErrors.size());
    }

    private void assertFloors(Result r, int total) {
        assertTrue(!r.models.isEmpty(), "must generate at least one model");
        assertTrue(r.fieldsGenerated >= total - 1, "must generate the bulk of fields, got " + r.fieldsGenerated);
        assertTrue(r.invalidTypes == 0, "generated " + r.invalidTypes + " invalid dataType(s)");
        assertTrue(r.typeMatched * 10 >= total * 8, "type accuracy below 80%: " + r.typeMatched + "/" + total);
    }

    // ---- helpers -----------------------------------------------------------

    private static List<Map<String, Object>> listOf(List<Map<String, Object>> l) {
        return l == null ? List.of() : l;
    }

    private static Map<String, Object> findField(List<Map<String, Object>> fields, String keyword) {
        String k = keyword.toLowerCase();
        for (Map<String, Object> f : fields) {
            String dn = str(f.get("displayName:zh-CN"));
            String code = str(f.get("code"));
            if ((dn != null && dn.toLowerCase().contains(k)) || (code != null && code.toLowerCase().contains(k))) {
                return f;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean nonEmpty(Object o) {
        return o != null && !o.toString().trim().isEmpty();
    }
}
