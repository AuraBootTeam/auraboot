package com.auraboot.framework.integration.bitemporal;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.mapper.BiTemporalMapper;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link BiTemporalService} using real PostgreSQL.
 *
 * <p>Replaces / complements the existing {@code BiTemporalServiceTest} unit
 * test which mocks the mapper and therefore can't catch SQL or close-and-insert
 * sequencing bugs.
 *
 * <p>Coverage:
 * <ul>
 *   <li>LIFECYCLE-1 put inserts a current record</li>
 *   <li>LIFECYCLE-2 correct closes prior tx period and inserts new version</li>
 *   <li>LIFECYCLE-3 terminate closes prior and writes terminated row with new validTo</li>
 *   <li>LIFECYCLE-4 getAsOf returns historical version, getHistory returns all</li>
 *   <li>LIFECYCLE-5 correct on missing entity throws IllegalStateException</li>
 *   <li>LIFECYCLE-6 multiple corrections produce monotonically increasing versionNo</li>
 * </ul>
 *
 * <p>Known gap (not exercised here, see HANDOVER 2026-05-05):
 * {@code findCurrent} does not lock the row, so two concurrent {@code correct()}
 * calls can both close the same tx period and insert two new versions, breaking
 * the bi-temporal invariant. Adding {@code FOR UPDATE} + a regression test is
 * a Phase C follow-up.
 */
@DisplayName("BiTemporalService integration tests")
class BiTemporalServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BiTemporalService biTemporalService;

    @Autowired
    private BiTemporalMapper mapper;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ENTITY_TYPE = "IT_ASSET";

    private String newEntityId() {
        // UniqueIdGenerator gives a tenant-scoped ULID that won't collide across runs.
        return "it-bt-" + UniqueIdGenerator.generate();
    }

    private JsonNode payload(String name, int version) {
        return objectMapper.createObjectNode()
                .put("name", name)
                .put("ver", version);
    }

    @Test
    @DisplayName("LIFECYCLE-1: put inserts a current record visible via getCurrent")
    void put_insertsRecordVisibleViaGetCurrent() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);

        BiTemporalRecord written = biTemporalService.put(
                ENTITY_TYPE, entityId, validFrom, validTo,
                payload("v1", 1), getTestUser().getId());

        assertThat(written).isNotNull();
        assertThat(written.getVersionNo()).isEqualTo(1);
        assertThat(written.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);

        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current).isNotNull();
        assertThat(current.getVersionNo()).isEqualTo(1);
        assertThat(current.getPayload().get("name").asText()).isEqualTo("v1");
        assertThat(current.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-2: correct closes prior tx period and inserts new version")
    void correct_closesPriorAndInsertsNewVersion() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);
        BiTemporalRecord corrected = biTemporalService.correct(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);

        assertThat(corrected.getVersionNo()).isEqualTo(2);
        assertThat(corrected.getPayload().get("name").asText()).isEqualTo("v2");
        assertThat(corrected.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);

        // getCurrent should now return v2
        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current.getVersionNo()).isEqualTo(2);

        // History contains both rows; v1's tx_to is closed (NOT infinity)
        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(2);
        BiTemporalRecord v1 = history.stream()
                .filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow();
        assertThat(v1.getTxTo()).isNotEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-3: terminate closes prior tx period and writes terminated row")
    void terminate_closesPriorAndWritesTerminatedRow() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);

        LocalDateTime terminationTime = LocalDateTime.now();
        biTemporalService.terminate(ENTITY_TYPE, entityId, terminationTime);

        // Latest version (v2) carries the original payload but a closed valid_to
        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(2);
        BiTemporalRecord terminated = history.stream()
                .filter(r -> r.getVersionNo() == 2).findFirst().orElseThrow();
        assertThat(terminated.getValidTo()).isEqualToIgnoringNanos(terminationTime);
        assertThat(terminated.getTxTo()).isEqualTo(BiTemporalRecord.INFINITY);
        // Old v1 has its tx_to closed
        BiTemporalRecord v1 = history.stream()
                .filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow();
        assertThat(v1.getTxTo()).isNotEqualTo(BiTemporalRecord.INFINITY);
    }

    @Test
    @DisplayName("LIFECYCLE-4: getAsOf returns the version valid at the given system time")
    void getAsOf_returnsHistoricalVersion() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        BiTemporalRecord v1 = biTemporalService.put(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);

        // Capture v1's UTC tx_from from the persisted row (service uses
        // LocalDateTime.ofInstant(Instant.now(), UTC), so we cannot use the
        // local-clock LocalDateTime.now() — see HANDOVER 2026-05-05 timezone
        // discussion. Use the read-back tx_from as the stable anchor.
        BiTemporalRecord v1ReadBack = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        LocalDateTime asOfV1 = v1ReadBack.getTxFrom();
        // valid_time must fall inside [validFrom, validTo) — pick same instant
        LocalDateTime asOfValid = v1ReadBack.getTxFrom();

        // Pause so the correction's tx_from is strictly later
        sleepMillis(50);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);

        // Querying with txTime=v1's tx_from should still return v1 payload
        BiTemporalRecord historical = biTemporalService.getAsOf(
                ENTITY_TYPE, entityId, asOfValid, asOfV1);
        assertThat(historical).isNotNull();
        assertThat(historical.getId()).isEqualTo(v1.getId());
        assertThat(historical.getPayload().get("name").asText()).isEqualTo("v1");

        // Querying with the current row's tx_from should return v2
        BiTemporalRecord v2ReadBack = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        BiTemporalRecord nowCurrent = biTemporalService.getAsOf(
                ENTITY_TYPE, entityId, v2ReadBack.getTxFrom(), v2ReadBack.getTxFrom());
        assertThat(nowCurrent).isNotNull();
        assertThat(nowCurrent.getPayload().get("name").asText()).isEqualTo("v2");
    }

    @Test
    @DisplayName("LIFECYCLE-5: correct on missing entity throws IllegalStateException")
    void correct_onMissingEntity_throws() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);

        assertThatThrownBy(() -> biTemporalService.correct(
                ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1),
                getTestUser().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No current record found for correction")
                .hasMessageContaining(entityId);
    }

    @Test
    @DisplayName("LIFECYCLE-6: repeated corrections produce monotonically increasing versionNo")
    void multipleCorrections_versionNoIsMonotonic() {
        String entityId = newEntityId();
        LocalDateTime validFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime validTo = LocalDateTime.now().plusYears(10);
        Long userId = getTestUser().getId();

        biTemporalService.put(ENTITY_TYPE, entityId, validFrom, validTo, payload("v1", 1), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v2", 2), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v3", 3), userId);
        biTemporalService.correct(ENTITY_TYPE, entityId, validFrom, validTo, payload("v4", 4), userId);

        List<BiTemporalRecord> history = biTemporalService.getHistory(ENTITY_TYPE, entityId);
        assertThat(history).hasSize(4);
        assertThat(history)
                .extracting(BiTemporalRecord::getVersionNo)
                .containsExactlyInAnyOrder(1, 2, 3, 4);

        BiTemporalRecord current = biTemporalService.getCurrent(ENTITY_TYPE, entityId);
        assertThat(current.getVersionNo()).isEqualTo(4);
        assertThat(current.getPayload().get("name").asText()).isEqualTo("v4");

        // All non-current versions must have a closed tx_to
        long openCount = history.stream()
                .filter(r -> r.getTxTo().equals(BiTemporalRecord.INFINITY))
                .count();
        assertThat(openCount).isEqualTo(1L);
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Sleep interrupted in test", e);
        }
    }
}
