package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.entity.UserDeactivation;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * UserDeactivationService integration tests.
 *
 * <p>DB: real PostgreSQL. No external mocks needed (pure DB flow).
 *
 * <p>Covers:
 * <ul>
 *   <li>D1-01: requestDeactivation creates COOLING_OFF record</li>
 *   <li>D1-02: duplicate request throws BusinessException</li>
 *   <li>D1-03: getDeactivationStatus returns the active record</li>
 *   <li>D1-04: cancelDeactivation removes the record</li>
 *   <li>D1-05: cancelDeactivation when no active record throws exception</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserDeactivationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserDeactivationService userDeactivationService;

    // ==================== D1-01: request deactivation ====================

    @Test
    @Order(1)
    @DisplayName("D1-01: requestDeactivation creates COOLING_OFF record")
    void requestDeactivation_createsRecord() {
        UserDeactivation result = userDeactivationService.requestDeactivation(
                getTestUser().getId(),
                "Integration test deactivation",
                "{\"agreed\": true, \"timestamp\": \"2026-03-17T00:00:00Z\"}");

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(getTestUser().getId());
        assertThat(result.getStatus()).isEqualTo("cooling_off");
        log.info("D1-01: created deactivation id={}", result.getId());
    }

    @Test
    @Order(2)
    @DisplayName("D1-02: duplicate requestDeactivation throws BusinessException")
    void requestDeactivation_duplicateRequest_throwsException() {
        assertThatThrownBy(() ->
                userDeactivationService.requestDeactivation(
                        getTestUser().getId(),
                        "Duplicate request",
                        "{\"agreed\": true}"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Order(3)
    @DisplayName("D1-03: getDeactivationStatus returns the COOLING_OFF record")
    void getDeactivationStatus_returnsActiveRecord() {
        UserDeactivation status = userDeactivationService.getDeactivationStatus(getTestUser().getId());

        assertThat(status).isNotNull();
        assertThat(status.getUserId()).isEqualTo(getTestUser().getId());
        assertThat(status.getStatus()).isEqualTo("cooling_off");
    }

    @Test
    @Order(4)
    @DisplayName("D1-04: cancelDeactivation removes the active record")
    void cancelDeactivation_removesRecord() {
        assertThatCode(() ->
                userDeactivationService.cancelDeactivation(getTestUser().getId()))
                .doesNotThrowAnyException();

        UserDeactivation status = userDeactivationService.getDeactivationStatus(getTestUser().getId());
        assertThat(status).isNull();
    }

    @Test
    @Order(5)
    @DisplayName("D1-05: cancelDeactivation with no active record throws exception")
    void cancelDeactivation_noActiveRecord_throwsException() {
        assertThatThrownBy(() ->
                userDeactivationService.cancelDeactivation(getTestUser().getId()))
                .isInstanceOf(Exception.class);
    }
}
