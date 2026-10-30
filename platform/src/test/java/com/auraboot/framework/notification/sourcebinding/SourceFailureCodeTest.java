package com.auraboot.framework.notification.sourcebinding;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class SourceFailureCodeTest {
    @Test void knownHostCodesRemainActionableWithoutReturningMessageText() {
        var expected=Map.of("NOTIFICATION_SOURCE_BINDING_INVALID",SourceFailureCode.BINDING_INVALID,
                "RELIABLE_ENVELOPE_CONFLICT",SourceFailureCode.ENVELOPE_CONFLICT,
                "NOTIFICATION_SOURCE_DELIVERY_BLOCKED",SourceFailureCode.DELIVERY_BLOCKED,
                "NOTIFICATION_SOURCE_WECHAT_DELIVERY_BLOCKED",SourceFailureCode.WECHAT_DELIVERY_BLOCKED);
        expected.forEach((message,code)->assertThat(SourceFailureCode.classify(new IllegalArgumentException(message))).isEqualTo(code));
    }
    @Test void concurrencyFailureHasItsOwnBoundedDiagnostic() {
        assertThat(SourceFailureCode.classify(new CannotAcquireLockException("private SQL"))).isEqualTo(SourceFailureCode.TRANSACTION_CONFLICT);
    }
    @Test void unknownAndNullMessagesCannotLeakPayloadOrCredentials() {
        for(String message: new String[]{null,"token=private-value","RELIABLE_ENVELOPE_CONFLICT private-value"}) {
            var result=SourceFailureCode.classify(new IllegalArgumentException(message));
            assertThat(result).isEqualTo(SourceFailureCode.PROCESSING_FAILED);
            assertThat(result.toString()).doesNotContain("private-value");
        }
    }
}
