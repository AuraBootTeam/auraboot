package com.auraboot.framework.workbench.service;

import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementReleaseFailureTest {
    @Test void knownConditionsRemainActionableWithoutPublishingInternalErrorText() {
        assertThat(AnnouncementReleaseWorker.failureCode(BusinessException.i18n("announcement.error.delivery_disabled"))).isEqualTo("DELIVERY_DISABLED");
        assertThat(AnnouncementReleaseWorker.failureCode(new AccessDeniedException("private authority evidence"))).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(AnnouncementReleaseWorker.failureCode(new BusinessException("private storage credential"))).isEqualTo("PUBLICATION_BLOCKED");
        assertThat(AnnouncementReleaseWorker.failureCode(new BusinessException("Empty message") {
            @Override public String getMessage(){return null;}
        })).isEqualTo("PUBLICATION_BLOCKED");
    }
    @Test void unknownPersistedDiagnosticsNeverBecomePublicMessages() {
        assertThat(AnnouncementReleaseService.publicFailureCode(null)).isEqualTo("PUBLICATION_BLOCKED");
        assertThat(AnnouncementReleaseService.publicFailureCode("private storage credential")).isEqualTo("PUBLICATION_BLOCKED");
        assertThat(AnnouncementReleaseService.publicFailureCode("TRANSIENT_DATABASE_FAILURE")).isEqualTo("TRANSIENT_DATABASE_FAILURE");
    }
}
