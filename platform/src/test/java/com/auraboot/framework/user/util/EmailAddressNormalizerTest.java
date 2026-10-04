package com.auraboot.framework.user.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressNormalizerTest {

    @Test
    void trimsAndLowercasesForStableUniqueness() {
        assertThat(EmailAddressNormalizer.normalize("  Customer.Name@QQ.COM  "))
                .isEqualTo("customer.name@qq.com");
    }

    @Test
    void returnsNullForMissingAddress() {
        assertThat(EmailAddressNormalizer.normalize(null)).isNull();
        assertThat(EmailAddressNormalizer.normalize("   ")).isNull();
    }
}
