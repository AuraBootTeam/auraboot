package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for IntentParser — no Spring context needed.
 */
class IntentParserTest {

    private IntentParser parser;

    @BeforeEach
    void setUp() {
        parser = new IntentParser();
    }

    // ─── CREATE ──────────────────────────────────────────────────────────────

    @Test
    void parse_createIntent_variousPatterns() {
        assertIntent("帮我新建一个客户", "create");
        assertIntent("创建一条线索", "create");
        assertIntent("添加一个联系人", "create");
        assertIntent("录入采购订单", "create");
    }

    // ─── QUERY ───────────────────────────────────────────────────────────────

    @Test
    void parse_queryIntent_variousPatterns() {
        assertIntent("查看最近的客户", "query");
        assertIntent("列出所有线索", "query");
        assertIntent("有哪些待审批的订单", "query");
        assertIntent("搜索张三的记录", "query");
    }

    // ─── TRANSITION ──────────────────────────────────────────────────────────

    @Test
    void parse_transitionIntent() {
        assertIntent("审批通过这个申请", "transition");
        assertIntent("把状态标记为完成", "transition");
        assertIntent("推进到下一阶段", "transition");
        assertIntent("驳回这个请求", "transition");
    }

    @Test
    void parse_transitionIntent_extendedPatterns() {
        assertIntent("批准这份合同", "transition");
        assertIntent("拒绝该采购申请", "transition");
        assertIntent("退回修改", "transition");
        assertIntent("撤回提交", "transition");
        assertIntent("关闭这个工单", "transition");
        assertIntent("归档已完成的项目", "transition");
        assertIntent("激活该账户", "transition");
        assertIntent("禁用这个用户", "transition");
        assertIntent("启用该功能", "transition");
        assertIntent("变更状态为已发货", "transition");
        assertIntent("提交审核", "transition");
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────────

    @Test
    void parse_updateIntent() {
        assertIntent("修改客户名称", "update");
        assertIntent("编辑这条记录", "update");
        assertIntent("更新联系方式", "update");
    }

    @Test
    void parse_updateIntent_extendedPatterns() {
        assertIntent("变更送货地址", "update");
        assertIntent("调整价格", "update");
        assertIntent("设置为高优先级", "update");
        assertIntent("改为线上支付", "update");
        assertIntent("换成新的负责人", "update");
    }

    // ─── DELETE ──────────────────────────────────────────────────────────────

    @Test
    void parse_deleteIntent_highConfidence() {
        IntentParser.IntentResult result = parser.parse("删除这个客户");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.88);
    }

    @Test
    void parse_deleteIntent_matchType() {
        IntentParser.IntentResult result = parser.parse("移除这条记录");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getMatchType()).isEqualTo("pattern");
    }

    // ─── ASSIGN ──────────────────────────────────────────────────────────────

    @Test
    void parse_assignIntent() {
        assertIntent("分配给张三", "assign");
        assertIntent("指派给销售团队", "assign");
        assertIntent("转交给李四处理", "assign");
        assertIntent("移交给下一个部门", "assign");
    }

    // ─── CONFIDENCE CALIBRATION ──────────────────────────────────────────────

    @Test
    void parse_patternMatch_confidenceAtLeast088() {
        IntentParser.IntentResult result = parser.parse("新建一个客户");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.88);
        assertThat(result.getMatchType()).isEqualTo("pattern");
    }

    @Test
    void parse_keywordMatch_singleHit_confidence065() {
        // "对比" only appears in keyword map (compare), no sentence pattern covers it
        IntentParser.IntentResult result = parser.parse("对比两份报告");
        assertThat(result.getIntent()).isEqualTo("compare");
        // single hit: 0.5 + 1 * 0.15 = 0.65
        assertThat(result.getConfidence()).isEqualTo(0.65, org.assertj.core.api.Assertions.within(0.01));
        assertThat(result.getMatchType()).isEqualTo("keyword");
    }

    @Test
    void parse_keywordMatch_multipleHits_cappedAt085() {
        // "对比 比较 差异 区别" — 4 compare keywords, none fire a sentence pattern
        // Expected: keyword match, confidence = min(0.85, 0.5 + 4*0.15) = 0.85
        IntentParser.IntentResult result = parser.parse("对比比较这两个方案的差异和区别");
        assertThat(result.getIntent()).isEqualTo("compare");
        assertThat(result.getMatchType()).isEqualTo("keyword");
        // 4 hits: 0.5 + 4*0.15 = 1.1, capped at 0.85
        assertThat(result.getConfidence()).isEqualTo(0.85, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void parse_emptyMessage_defaultsToQuery() {
        IntentParser.IntentResult result = parser.parse("");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getConfidence()).isLessThan(0.5);
    }

    @Test
    void parse_nullMessage_defaultsToQuery() {
        IntentParser.IntentResult result = parser.parse(null);
        assertThat(result.getIntent()).isEqualTo("query");
    }

    @Test
    void parse_unknownMessage_defaultsToQuery() {
        IntentParser.IntentResult result = parser.parse("你好世界");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getMatchType()).isEqualTo("default");
    }

    // ─── ENGLISH ────────────────────────────────────────────────────────────

    @Test
    void parse_english_createIntent() {
        assertIntent("create a new customer", "create");
        assertIntent("add a contact", "create");
        assertIntent("register a new account", "create");
    }

    @Test
    void parse_english_queryIntent() {
        assertIntent("show me all leads", "query");
        assertIntent("list recent orders", "query");
        assertIntent("find customer by name", "query");
        assertIntent("search for pending invoices", "query");
    }

    @Test
    void parse_english_deleteIntent() {
        IntentParser.IntentResult result = parser.parse("delete this record");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.88);
    }

    @Test
    void parse_english_updateIntent() {
        assertIntent("update the customer name", "update");
        assertIntent("edit this record", "update");
        assertIntent("modify the address", "update");
        assertIntent("change the status", "update");
    }

    @Test
    void parse_english_transitionIntent() {
        assertIntent("approve this request", "transition");
        assertIntent("reject the application", "transition");
        assertIntent("submit for review", "transition");
        assertIntent("archive completed projects", "transition");
        assertIntent("mark as done", "transition");
    }

    @Test
    void parse_english_assignIntent() {
        assertIntent("assign to John", "assign");
        assertIntent("delegate this task", "assign");
        assertIntent("reassign the ticket", "assign");
    }

    @Test
    void parse_english_analyzeIntent() {
        assertIntent("analyze sales trends", "analyze");
        assertIntent("give me the breakdown of revenue", "analyze");
    }

    @Test
    void parse_english_exportIntent() {
        assertIntent("export to csv", "export");
        assertIntent("download the report", "export");
    }

    // ─── JAPANESE ──────────────────────────────────────────────────────────

    @Test
    void parse_japanese_createIntent() {
        assertIntent("新しい顧客を作成してください", "create");
        assertIntent("連絡先を追加", "create");
        assertIntent("新規登録する", "create");
    }

    @Test
    void parse_japanese_queryIntent() {
        assertIntent("顧客一覧を表示", "query");
        assertIntent("注文を検索する", "query");
        assertIntent("最近のリードを見る", "query");
    }

    @Test
    void parse_japanese_deleteIntent() {
        IntentParser.IntentResult result = parser.parse("このレコードを削除");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.88);
    }

    @Test
    void parse_japanese_updateIntent() {
        assertIntent("顧客名を編集", "update");
        assertIntent("住所を変更する", "update");
        assertIntent("情報を更新", "update");
    }

    @Test
    void parse_japanese_transitionIntent() {
        assertIntent("この申請を承認", "transition");
        assertIntent("リクエストを却下する", "transition");
        assertIntent("レビューに提出", "transition");
    }

    @Test
    void parse_japanese_analyzeIntent() {
        assertIntent("売上を分析", "analyze");
        assertIntent("月次統計を見せて", "analyze");
    }

    // ─── LLM FALLBACK ─────────────────────────────────────────────────────

    @Test
    void parse_unknownLanguage_noLlm_defaultsToQuery() {
        // Korean, no LLM configured → default
        IntentParser.IntentResult result = parser.parse("고객을 만들어 주세요");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getMatchType()).isEqualTo("default");
        assertThat(result.getConfidence()).isLessThan(0.5);
    }

    @Test
    void parse_matchType_pattern_forEnglish() {
        IntentParser.IntentResult result = parser.parse("create a new lead");
        assertThat(result.getMatchType()).isEqualTo("pattern");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.88);
    }

    @Test
    void parse_matchType_keyword_forEnglish() {
        // "compare" is in both pattern and keyword — pattern fires first with higher confidence
        IntentParser.IntentResult result = parser.parse("compare these two reports");
        assertThat(result.getIntent()).isEqualTo("compare");
        assertThat(result.getConfidence()).isGreaterThan(0.5);
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private void assertIntent(String message, String expectedIntent) {
        IntentParser.IntentResult result = parser.parse(message);
        assertThat(result.getIntent())
                .as("Expected intent '%s' for message: %s", expectedIntent, message)
                .isEqualTo(expectedIntent);
        assertThat(result.getConfidence())
                .as("Confidence should be > 0.5 for message: %s", message)
                .isGreaterThan(0.5);
    }
}
