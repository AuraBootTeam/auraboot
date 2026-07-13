package com.auraboot.framework.bpm.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule → i18n key convention shared by the command pre-action pipeline and the BPM
 * serviceTask delegate: a rule reports a bare reason, the catalog holds the text under
 * {@code error.<ruleCode>.<reason>}.
 */
class RuleReasonMessagesTest {

    @Test
    void buildsScopedKeyFromRuleCodeAndReason() {
        assertThat(RuleReasonMessages.reasonKey(
                "wd_leave_validation", "annual_balance_not_found", "bpm.rule.execution_failed"))
                .isEqualTo("$i18n:error.wd_leave_validation.annual_balance_not_found");
    }

    @Test
    void fallsBackWhenRuleReportsNoReason() {
        assertThat(RuleReasonMessages.reasonKey("wd_leave_validation", null, "bpm.rule.execution_failed"))
                .isEqualTo("$i18n:bpm.rule.execution_failed");
        assertThat(RuleReasonMessages.reasonKey("wd_leave_validation", "  ", "bpm.rule.execution_failed"))
                .isEqualTo("$i18n:bpm.rule.execution_failed");
    }

    @Test
    void leavesAnAlreadyQualifiedKeyAlone() {
        // A rule may report a fully-qualified key of its own; don't double-scope it.
        assertThat(RuleReasonMessages.reasonKey("wd_leave_validation", "$i18n:error.custom.key", "fb"))
                .isEqualTo("$i18n:error.custom.key");
        assertThat(RuleReasonMessages.i18nKey("$i18n:already.prefixed"))
                .isEqualTo("$i18n:already.prefixed");
    }

    @Test
    void unscopedReasonStillGetsLocalized() {
        assertThat(RuleReasonMessages.reasonKey(null, "some_reason", "fb"))
                .isEqualTo("$i18n:some_reason");
    }

    @Test
    void i18nKeyIgnoresBlankInput() {
        assertThat(RuleReasonMessages.i18nKey(null)).isNull();
        assertThat(RuleReasonMessages.i18nKey("")).isEmpty();
    }
}
