package com.auraboot.framework.notification.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageAndResultTest {

    @Test
    void notificationResult_okAndFailExposeFlagsAndMessage() {
        NotificationResult ok = NotificationResult.ok();
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getErrorMessage()).isNull();

        NotificationResult fail = NotificationResult.fail("oops");
        assertThat(fail.isSuccess()).isFalse();
        assertThat(fail.getErrorMessage()).isEqualTo("oops");
    }

    @Test
    void notificationMessage_builderPopulatesAllFields() {
        NotificationMessage msg = NotificationMessage.builder()
                .tenantId(1L)
                .recipientUserIds(List.of(2L, 3L))
                .templateCode("tpl")
                .subject("sub")
                .body("body")
                .category("cat")
                .sourceType("st")
                .sourceId("sid")
                .extras(Map.of("k", "v"))
                .build();
        assertThat(msg.getTenantId()).isEqualTo(1L);
        assertThat(msg.getRecipientUserIds()).containsExactly(2L, 3L);
        assertThat(msg.getTemplateCode()).isEqualTo("tpl");
        assertThat(msg.getSubject()).isEqualTo("sub");
        assertThat(msg.getBody()).isEqualTo("body");
        assertThat(msg.getCategory()).isEqualTo("cat");
        assertThat(msg.getSourceType()).isEqualTo("st");
        assertThat(msg.getSourceId()).isEqualTo("sid");
        assertThat(msg.getExtras()).containsEntry("k", "v");
    }
}
