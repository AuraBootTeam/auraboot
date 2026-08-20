package com.auraboot.framework.application.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhiteListTest {

    @Test
    void exposesOnlyTheExactAuraQrStripeWebhookPath() {
        assertThat(WhiteList.whiteList)
                .contains("/api/qr/label-ai/billing/webhooks/stripe")
                .doesNotContain("/api/qr/label-ai/billing/webhooks/**");
    }
}
