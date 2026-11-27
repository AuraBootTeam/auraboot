package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProcurementMiniProgramLinkTest {
    @Test void selectsDetailOnlyForOneVerifiedTarget() {
        var link = new SourceBindingTypes.MiniProgramLink("pages/notification-entry/index", "quote");
        assertThat(link.path(List.of("quote-A"))).isEqualTo("pages/notification-entry/index?kind=quote&id=quote-A");
        assertThat(link.path(List.of("quote-A", "quote-B"))).isEqualTo("pages/notification-entry/index?kind=quote");
        assertThatThrownBy(() -> link.path(List.of("quote-A&kind=order"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> link.path(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsAnExternalPageAndNonCanonicalNavigation() {
        assertThatThrownBy(() -> new SourceBindingTypes.MiniProgramLink("https://wrong.test", "quote"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NotificationDeliveryPlanResolver.validMiniProgramPath("pages/notification-entry/index?kind=order&id=po_1")).isTrue();
        assertThat(NotificationDeliveryPlanResolver.validMiniProgramPath("pages/notification-entry/index?kind=order&id=po%2f1")).isFalse();
        assertThat(NotificationDeliveryPlanResolver.validMiniProgramPath("pages/../index?kind=order")).isFalse();
    }
}
