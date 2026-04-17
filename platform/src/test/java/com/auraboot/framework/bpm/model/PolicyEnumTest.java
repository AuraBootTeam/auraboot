package com.auraboot.framework.bpm.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyEnumTest {

    @Test void withdrawPolicyFromCodeLowercase() {
        assertThat(WithdrawPolicy.fromCode("strict")).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(WithdrawPolicy.fromCode("LOOSE")).isEqualTo(WithdrawPolicy.LOOSE);
        assertThat(WithdrawPolicy.fromCode(null)).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(WithdrawPolicy.fromCode("")).isEqualTo(WithdrawPolicy.STRICT);
    }

    @Test void withdrawPolicyRejectsUnknown() {
        assertThatThrownBy(() -> WithdrawPolicy.fromCode("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ccPolicyDefaultsToAll() {
        assertThat(CcPolicy.fromCode(null)).isEqualTo(CcPolicy.ALL);
        assertThat(CcPolicy.fromCode("initiator")).isEqualTo(CcPolicy.INITIATOR);
        assertThat(CcPolicy.fromCode("assignee")).isEqualTo(CcPolicy.ASSIGNEE);
    }
}
