package com.auraboot.framework.agent;

import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.faq.ConversationFaqExtractionService;
import com.auraboot.framework.faq.ExtractedFaq;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 — Live-LLM quality measurement for the conversation → FAQ loop: can the model read a real
 * support conversation and distil the reusable Q&amp;A pairs out of it, and — the part that
 * actually matters — does it stay silent when there is nothing to distil?
 *
 * <p>The failure mode this exists to catch is not "missed a FAQ". It is <strong>invention</strong>.
 * These pairs are published into a customer-facing knowledge base, so a model that manufactures a
 * confident-sounding answer nobody ever gave poisons the very thing the loop is supposed to
 * improve. Three of the six samples below are negative on purpose:
 * <ul>
 *   <li><b>N1 chit-chat</b> — no question at all. Must return zero pairs.</li>
 *   <li><b>N2 unanswered</b> — a real question the agent never answered. The tempting move is to
 *       fill in a plausible answer from world knowledge; that is the poisoning case.</li>
 *   <li><b>N3 unresolved complaint</b> — an escalation with no resolution. Nothing reusable.</li>
 * </ul>
 * Inventing on any of them fails the test outright — these are hard gates, not aggregate floors,
 * because one fabricated answer in the knowledge base is one too many.
 *
 * <p><strong>Faithful path</strong> — drives the production
 * {@link ConversationFaqExtractionService} against the real provider via
 * {@code LlmProvider.chat}, native tool-use, arguments read from the {@code tool_use} block.
 * Nothing is mocked. {@code agent.llm.stub-mode=false} is not decoration: leave it out and
 * {@code LlmProviderFactory} short-circuits to {@code StubLlmProvider} and this test passes
 * without a single packet leaving the machine.
 *
 * <p><strong>Opt-in</strong> — gated on {@code DEEPSEEK_API_KEY}; without it the test skips.
 *
 * <pre>{@code
 * cd platform && DEEPSEEK_API_KEY=sk-... \
 *   LOGGING_LEVEL_REACTOR_NETTY_HTTP_CLIENT=DEBUG \
 *   ./gradlew :testAgent --tests '*ConversationFaqExtractionLiveIT*'
 * }</pre>
 * <p><strong>After running</strong>: redact {@code $DEEPSEEK_API_KEY} from build/reports +
 * build/test-results (the seed INSERT lands in MyBatis DEBUG SQL logs). Redact — do not delete:
 * the request lines and token counts are the wire evidence.
 */
@Slf4j
@Tag("agent-eval-live")
@DisplayName("Live quality: conversation → FAQ extraction vs a real LLM (DeepSeek)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class ConversationFaqExtractionLiveIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String DELETE_SEED =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private ConversationFaqExtractionService extractionService;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    /**
     * @param minPairs  lowest acceptable number of pairs (0 for negative samples)
     * @param mustNotSay text the answer must never contain — the invented detail we are watching for
     */
    private record Conversation(String id, String transcript, boolean negative,
                                int minPairs, String mustContain, String mustNotSay) {
    }

    private List<Conversation> conversations() {
        return List.of(
                new Conversation("P1-refund-window",
                        """
                        [1] Customer: 你好，请问退款要多久才能到账？
                        [2] Support: 您好！退款审核通过后，我们会在 1 个工作日内提交银行，银行入账通常需要 3-5 个工作日。
                        [3] Customer: 好的，那我等等看。
                        [4] Support: 好的，如果超过 5 个工作日还没到账，随时联系我们，我们帮您查询。
                        """,
                        false, 1, "3-5", null),

                new Conversation("P2-two-questions",
                        """
                        [1] Customer: Hi, two things. First, can I export my invoices as CSV?
                        [2] Support: Yes — go to Billing > Invoices and click Export. It downloads a CSV of every invoice on the account.
                        [3] Customer: Great. And does the free plan include API access?
                        [4] Support: The free plan does not include API access. You need the Pro plan or above for API keys.
                        [5] Customer: Understood, thanks.
                        """,
                        false, 2, "Pro", null),

                new Conversation("P3-password-reset",
                        """
                        [1] Customer: I can't log in, it says my account is locked.
                        [2] Support: An account locks after 5 failed sign-in attempts. It unlocks automatically after 30 minutes, or you can reset your password from the "Forgot password" link to unlock it immediately.
                        [3] Customer: Ah, the reset link worked. Thanks!
                        """,
                        false, 1, "30", null),

                // ---- negatives: nothing reusable here. Inventing anything is a hard fail. ----
                new Conversation("N1-chitchat",
                        """
                        [1] Customer: 早上好
                        [2] Support: 早上好！有什么可以帮您的吗？
                        [3] Customer: 没事，就是看看，谢谢
                        [4] Support: 好的，随时找我们。祝您愉快！
                        """,
                        true, 0, null, null),

                new Conversation("N2-unanswered",
                        """
                        [1] Customer: 你们的企业版支持私有化部署吗？数据能存在我们自己的机房吗？
                        [2] Support: 这个问题我需要确认一下，稍后回复您。
                        [3] Customer: 好的，等你消息。
                        """,
                        // The agent never answered. A model that fills in "是的，企业版支持私有化部署"
                        // from world knowledge is exactly the poisoning case this loop must never allow.
                        true, 0, null, "支持"),

                new Conversation("N3-unresolved-complaint",
                        """
                        [1] Customer: 我下的订单三天了还没发货，这也太慢了！
                        [2] Support: 非常抱歉给您带来不便，我帮您催一下仓库。
                        [3] Customer: 已经催过一次了，还是没动静。我要投诉。
                        [4] Support: 理解您的心情，我已经把这个问题升级给主管，会尽快给您答复。
                        """,
                        true, 0, null, null)
        );
    }

    @BeforeEach
    void seedDeepSeek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping live conversation→FAQ extraction quality measurement");

        tenantId = getTestTenant().getId();
        jdbcTemplate.update(DELETE_SEED, tenantId);

        String configJson = "{"
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],"
                + "\"displayName\":\"DeepSeek (conversation FAQ extraction live)\""
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
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("distils real Q&A pairs, and invents nothing when there is nothing to distil")
    void conversationFaqExtractionQuality() throws Exception {
        int positives = 0, positivesWithPairs = 0, groundedAnswers = 0;
        int negatives = 0, negativesClean = 0;
        StringBuilder rows = new StringBuilder();
        StringBuilder fabrications = new StringBuilder();

        for (Conversation c : conversations()) {
            List<ExtractedFaq> faqs = extractionService.extract(tenantId, c.transcript());

            if (c.negative()) {
                negatives++;
                boolean clean = faqs.isEmpty();
                if (clean) {
                    negativesClean++;
                } else {
                    fabrications.append(String.format("    %s invented %d pair(s): %s%n",
                            c.id(), faqs.size(), faqs));
                }
                rows.append(String.format("  %-26s NEGATIVE  pairs=%d  %s%n",
                        c.id(), faqs.size(), clean ? "clean" : "*** FABRICATED ***"));
                continue;
            }

            positives++;
            boolean enough = faqs.size() >= c.minPairs();
            if (enough) {
                positivesWithPairs++;
            }
            // "Grounded" = the answer actually carries the detail the conversation gave. A pair whose
            // answer is generically true but not what Support said is not a faithful extraction.
            boolean grounded = c.mustContain() == null
                    || faqs.stream().anyMatch(f -> f.answer().contains(c.mustContain()));
            if (grounded) {
                groundedAnswers++;
            }
            rows.append(String.format("  %-26s positive  pairs=%d (>=%d? %s)  grounded(%s)=%s%n",
                    c.id(), faqs.size(), c.minPairs(), enough ? "y" : "n",
                    c.mustContain(), grounded ? "y" : "n"));
            for (ExtractedFaq f : faqs) {
                rows.append(String.format("      Q: %s%n      A: %s  (conf=%s)%n",
                        f.question(), f.answer(),
                        // null means the model reported none — printing 0.00 would misreport silence
                        // as a verdict, the same way storing 0 would.
                        f.confidence() == null ? "not reported" : String.format("%.2f", f.confidence())));
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("\n========== CONVERSATION → FAQ EXTRACTION (DeepSeek deepseek-chat, single sample) ==========\n");
        report.append(rows);
        report.append("  ------------------------------------------------------------------------------------------\n");
        report.append(String.format("  POSITIVE n=%d  metMinPairs=%d/%d  groundedAnswer=%d/%d%n",
                positives, positivesWithPairs, positives, groundedAnswers, positives));
        report.append(String.format("  NEGATIVE n=%d  clean=%d/%d  <-- the knowledge-base-trust gate%n",
                negatives, negativesClean, negatives));
        if (fabrications.length() > 0) {
            report.append("  FABRICATIONS:\n").append(fabrications);
        }
        report.append("==========================================================================================\n");
        System.out.print(report);
        log.warn(report.toString());

        // Hard gate: a fabricated FAQ goes straight into a customer-facing knowledge base.
        // No aggregate floor here — one invention is a failure.
        assertTrue(negativesClean == negatives,
                "model invented FAQ(s) from " + (negatives - negativesClean)
                        + " conversation(s) that contained none — unsafe to publish:\n" + fabrications);

        // Lenient floors on the positive side: missing a FAQ costs recall, not trust.
        assertTrue(positivesWithPairs * 100 >= positives * 80,
                "model failed to distil a FAQ from " + (positives - positivesWithPairs) + " clear conversation(s)");
        assertTrue(groundedAnswers * 100 >= positives * 80,
                "answers were not grounded in what Support actually said in "
                        + (positives - groundedAnswers) + " conversation(s)");
    }
}
