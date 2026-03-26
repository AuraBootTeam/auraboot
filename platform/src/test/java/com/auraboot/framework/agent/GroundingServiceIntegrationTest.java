package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.agent.service.IntentParser;
import com.auraboot.framework.agent.service.ObjectResolver;
import com.auraboot.framework.agent.service.RiskEvaluator;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for D1 Grounding layer:
 * IntentParser, ObjectResolver, RiskEvaluator, GroundingService (full pipeline).
 * Requires ab_object_alias seed data (tenant_id = -1) and published meta models.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class GroundingServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IntentParser intentParser;

    @Autowired
    private ObjectResolver objectResolver;

    @Autowired
    private RiskEvaluator riskEvaluator;

    @Autowired
    private GroundingService groundingService;

    // ========== IntentParser ==========

    @Test
    void testIntentParser_queryIntent() {
        IntentParser.IntentResult result = intentParser.parse("看看客户情况");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void testIntentParser_createIntent() {
        IntentParser.IntentResult result = intentParser.parse("帮我创建一个新线索");
        assertThat(result.getIntent()).isEqualTo("create");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void testIntentParser_deleteIntent() {
        IntentParser.IntentResult result = intentParser.parse("删除这条记录");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void testIntentParser_defaultFallback() {
        IntentParser.IntentResult result = intentParser.parse("你好");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getConfidence()).isLessThanOrEqualTo(0.5);
    }

    // ========== ObjectResolver ==========

    @Test
    void testObjectResolver_aliasMatch() {
        Long tenantId = getTestTenant().getId();
        ObjectResolver.ObjectResult result = objectResolver.resolve(tenantId, "查一下客户列表");
        assertThat(result.getModelCode()).isEqualTo("crm_account");
        assertThat(result.getMatchType()).isEqualTo("alias");
    }

    @Test
    void testObjectResolver_leadAlias() {
        Long tenantId = getTestTenant().getId();
        ObjectResolver.ObjectResult result = objectResolver.resolve(tenantId, "线索有多少");
        assertThat(result.getModelCode()).isEqualTo("crm_lead");
    }

    @Test
    void testObjectResolver_noMatch() {
        Long tenantId = getTestTenant().getId();
        ObjectResolver.ObjectResult result = objectResolver.resolve(tenantId, "今天天气怎样");
        assertThat(result.getModelCode()).isNull();
    }

    // ========== RiskEvaluator ==========

    @Test
    void testRiskEvaluator_readIsL0() {
        String risk = riskEvaluator.evaluate("query", 1);
        assertThat(risk).isEqualTo("L0");
    }

    @Test
    void testRiskEvaluator_deleteIsL4() {
        String risk = riskEvaluator.evaluate("delete", 1);
        assertThat(risk).isEqualTo("L4");
    }

    // ========== GroundingService (full pipeline) ==========

    @Test
    void testGroundingService_fullPipeline() {
        Long tenantId = getTestTenant().getId();
        GroundingService.GroundingContext ctx = GroundingService.GroundingContext.builder().build();

        BusinessIntentFrame bif = groundingService.ground(tenantId, "查一下CRM线索", ctx);

        assertThat(bif.getIntent()).isEqualTo("query");
        assertThat(bif.getObject()).isEqualTo("crm_lead");
        assertThat(bif.getConfidence()).isNotNull();
        assertThat(bif.getConfidence().getOverall()).isGreaterThan(0.5);
        assertThat(bif.getRiskLevel()).isEqualTo("L0");
    }

    @Test
    void testGroundingService_qualityGate_lowConfidence() {
        Long tenantId = getTestTenant().getId();
        GroundingService.GroundingContext ctx = GroundingService.GroundingContext.builder().build();

        BusinessIntentFrame bif = groundingService.ground(tenantId, "asdfghjkl", ctx);

        String reason = groundingService.checkQualityGate(bif);
        assertThat(reason).isNotNull();
    }
}
