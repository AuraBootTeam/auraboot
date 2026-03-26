package com.auraboot.framework.inbox;

import com.auraboot.framework.inbox.dto.CheckinRequest;
import com.auraboot.framework.inbox.dto.CheckinResponse;
import com.auraboot.framework.inbox.service.CheckinService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CheckinService covering GPS check-in creation and retrieval.
 * Uses NOT_SUPPORTED propagation so data persists between ordered tests.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("Checkin Service Integration Tests (CHK-01~CHK-04)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckinIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CheckinService checkinService;

    private final String runId = "chk-" + System.currentTimeMillis();
    private final String testModelCode = "test_entity_model";
    private final Long testRecordId = System.currentTimeMillis();

    // Cross-test state
    private Long checkinId;
    private Long entityCheckinId;

    // ==================== CHK-01 ====================

    @Test
    @Order(1)
    @DisplayName("CHK-01: checkin creates record with correct latitude and longitude")
    void chk01_checkinCreatesRecord() {
        CheckinRequest request = new CheckinRequest();
        request.setLatitude(39.9042);
        request.setLongitude(116.4074);
        request.setAddress(runId + " Beijing Office");
        request.setNotes(runId + " daily checkin");

        CheckinResponse result = checkinService.checkin(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getLatitude()).isEqualTo(39.9042);
        assertThat(result.getLongitude()).isEqualTo(116.4074);

        checkinId = result.getId();
        log.info("CHK-01: created checkin id={}, lat={}, lng={}", checkinId, result.getLatitude(), result.getLongitude());
    }

    // ==================== CHK-02 ====================

    @Test
    @Order(2)
    @DisplayName("CHK-02: getRecent returns recent checkins including created one")
    void chk02_getRecentReturnsList() {
        assertThat(checkinId).as("checkinId must be set by CHK-01").isNotNull();

        List<CheckinResponse> recent = checkinService.getRecent(null, 20);

        assertThat(recent).isNotNull();
        assertThat(recent).isNotEmpty();
        assertThat(recent).extracting(CheckinResponse::getId).contains(checkinId);

        log.info("CHK-02: getRecent returned {} items, found checkinId={}", recent.size(), checkinId);
    }

    // ==================== CHK-03 ====================

    @Test
    @Order(3)
    @DisplayName("CHK-03: checkin with modelCode+recordId links to entity, getByEntity returns it")
    void chk03_checkinWithEntityLinkReturnsViaGetByEntity() {
        CheckinRequest request = new CheckinRequest();
        request.setLatitude(31.2304);
        request.setLongitude(121.4737);
        request.setAddress(runId + " Shanghai Office");
        request.setModelCode(testModelCode);
        request.setRecordId(testRecordId);

        CheckinResponse result = checkinService.checkin(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getModelCode()).isEqualTo(testModelCode);
        assertThat(result.getRecordId()).isEqualTo(testRecordId);

        entityCheckinId = result.getId();

        List<CheckinResponse> byEntity = checkinService.getByEntity(testModelCode, testRecordId);

        assertThat(byEntity).isNotNull();
        assertThat(byEntity).isNotEmpty();
        assertThat(byEntity).extracting(CheckinResponse::getId).contains(entityCheckinId);

        log.info("CHK-03: entity checkin id={}, getByEntity returned {} items", entityCheckinId, byEntity.size());
    }

    // ==================== CHK-04 ====================

    @Test
    @Order(4)
    @DisplayName("CHK-04: getRecent with beforeId returns only older checkins")
    void chk04_getRecentWithBeforeIdReturnsOlderOnly() {
        assertThat(checkinId).as("checkinId must be set by CHK-01").isNotNull();
        assertThat(entityCheckinId).as("entityCheckinId must be set by CHK-03").isNotNull();

        // entityCheckinId was created after checkinId, so using entityCheckinId as beforeId
        // should return checkinId but not entityCheckinId itself
        List<CheckinResponse> older = checkinService.getRecent(entityCheckinId, 20);

        assertThat(older).isNotNull();
        // All returned ids must be less than entityCheckinId (since IDs are auto-increment)
        assertThat(older).allSatisfy(c ->
                assertThat(c.getId()).isLessThan(entityCheckinId));

        log.info("CHK-04: getRecent(beforeId={}) returned {} items", entityCheckinId, older.size());
    }
}
