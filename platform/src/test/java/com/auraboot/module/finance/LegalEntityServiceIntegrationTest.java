package com.auraboot.module.finance;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.module.finance.dto.LegalEntityCreateRequest;
import com.auraboot.module.finance.dto.LegalEntityTree;
import com.auraboot.module.finance.entity.LegalEntity;
import com.auraboot.module.finance.service.LegalEntityService;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link LegalEntityService}.
 *
 * <p>Test plan:
 * <ul>
 *   <li>LE-01: create entity with valid data succeeds</li>
 *   <li>LE-02: duplicate entityCode within same tenant throws</li>
 *   <li>LE-03: findAll returns only tenant-owned entities</li>
 *   <li>LE-04: findById returns correct entity</li>
 *   <li>LE-05: update entity persists changes</li>
 *   <li>LE-06: delete entity succeeds when no children</li>
 *   <li>LE-07: delete throws when entity has children</li>
 *   <li>LE-08: buildHierarchy constructs correct parent-child tree</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegalEntityServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LegalEntityService legalEntityService;

    private final String runId = String.valueOf(System.currentTimeMillis());

    // ==================== LE-01 ====================

    @Test
    @Order(1)
    @DisplayName("LE-01: create entity with valid data succeeds")
    void createLegalEntity_withValidData_succeeds() {
        LegalEntityCreateRequest req = buildReq("HQ-" + runId, "Headquarters " + runId, null, "cny", null, true);

        LegalEntity saved = legalEntityService.create(req);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPid()).hasSize(26);
        assertThat(saved.getEntityCode()).isEqualTo("HQ-" + runId);
        assertThat(saved.getEntityName()).isEqualTo("Headquarters " + runId);
        assertThat(saved.getCurrency()).isEqualTo("cny");
        assertThat(saved.getIsParent()).isTrue();
        assertThat(saved.getTenantId()).isEqualTo(MetaContext.getCurrentTenantId());
        log.info("LE-01 passed: created entity id={}", saved.getId());
    }

    // ==================== LE-02 ====================

    @Test
    @Order(2)
    @DisplayName("LE-02: duplicate entityCode within same tenant throws IllegalArgumentException")
    void createLegalEntity_duplicateCode_throws() {
        String code = "DUPL-" + runId;
        legalEntityService.create(buildReq(code, "First " + runId, null, "usd", null, false));

        assertThatThrownBy(() ->
                legalEntityService.create(buildReq(code, "Second " + runId, null, "usd", null, false))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining(code);
    }

    // ==================== LE-03 ====================

    @Test
    @Order(3)
    @DisplayName("LE-03: findAll returns only current-tenant entities")
    void findAll_returnsTenantIsolatedResults() {
        Long tenantId = MetaContext.getCurrentTenantId();
        legalEntityService.create(buildReq("T3A-" + runId, "Entity A " + runId, null, "cny", null, false));
        legalEntityService.create(buildReq("T3B-" + runId, "Entity B " + runId, null, "usd", null, false));

        List<LegalEntity> all = legalEntityService.findAll(tenantId);

        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(e -> e.getTenantId().equals(tenantId));
        assertThat(all.stream().map(LegalEntity::getEntityCode))
                .contains("T3A-" + runId, "T3B-" + runId);
    }

    // ==================== LE-04 ====================

    @Test
    @Order(4)
    @DisplayName("LE-04: findById returns the correct entity")
    void findById_returnsCorrectEntity() {
        LegalEntity created = legalEntityService.create(
                buildReq("T4-" + runId, "Find By Id Test " + runId, null, "usd", null, false));

        LegalEntity found = legalEntityService.findById(created.getId());

        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getEntityCode()).isEqualTo("T4-" + runId);
    }

    // ==================== LE-05 ====================

    @Test
    @Order(5)
    @DisplayName("LE-05: update entity persists changes correctly")
    void updateLegalEntity_persistsChanges() {
        LegalEntity created = legalEntityService.create(
                buildReq("T5-" + runId, "Original Name " + runId, null, "cny", null, false));

        LegalEntityCreateRequest updateReq = buildReq("T5U-" + runId, "Updated Name " + runId, null, "usd",
                new BigDecimal("51.00"), false);
        LegalEntity updated = legalEntityService.update(created.getId(), updateReq);

        assertThat(updated.getEntityCode()).isEqualTo("T5U-" + runId);
        assertThat(updated.getEntityName()).isEqualTo("Updated Name " + runId);
        assertThat(updated.getCurrency()).isEqualTo("usd");
        assertThat(updated.getOwnershipPct()).isEqualByComparingTo("51.00");
    }

    // ==================== LE-06 ====================

    @Test
    @Order(6)
    @DisplayName("LE-06: delete entity with no children succeeds")
    void deleteLegalEntity_noChildren_succeeds() {
        LegalEntity created = legalEntityService.create(
                buildReq("T6-" + runId, "Deletable " + runId, null, "cny", null, false));

        assertThatCode(() -> legalEntityService.delete(created.getId()))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> legalEntityService.findById(created.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== LE-07 ====================

    @Test
    @Order(7)
    @DisplayName("LE-07: delete entity with children throws IllegalStateException")
    void deleteLegalEntity_withChildren_throws() {
        LegalEntity parent = legalEntityService.create(
                buildReq("T7P-" + runId, "Parent " + runId, null, "cny", null, true));
        legalEntityService.create(
                buildReq("T7C-" + runId, "Child " + runId, parent.getId(), "cny",
                        new BigDecimal("100.00"), false));

        assertThatThrownBy(() -> legalEntityService.delete(parent.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child");
    }

    // ==================== LE-08 ====================

    @Test
    @Order(8)
    @DisplayName("LE-08: buildHierarchy returns correct parent-child tree")
    void buildHierarchy_withParentChild_returnsTree() {
        Long tenantId = MetaContext.getCurrentTenantId();

        LegalEntity hq = legalEntityService.create(
                buildReq("T8HQ-" + runId, "HQ " + runId, null, "cny", null, true));
        LegalEntity sub1 = legalEntityService.create(
                buildReq("T8S1-" + runId, "Sub1 " + runId, hq.getId(), "cny",
                        new BigDecimal("80.00"), false));
        legalEntityService.create(
                buildReq("T8S2-" + runId, "Sub2 " + runId, hq.getId(), "usd",
                        new BigDecimal("60.00"), false));

        List<LegalEntityTree> roots = legalEntityService.buildHierarchy(tenantId);

        // Find the HQ node we created
        LegalEntityTree hqNode = roots.stream()
                .filter(t -> t.getEntity().getId().equals(hq.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("HQ node not found in tree roots"));

        assertThat(hqNode.getChildren()).hasSize(2);
        assertThat(hqNode.getChildren().stream().map(t -> t.getEntity().getId()))
                .contains(sub1.getId());
        log.info("LE-08 passed: hierarchy built, HQ has {} children", hqNode.getChildren().size());
    }

    // ==================== Helpers ====================

    private LegalEntityCreateRequest buildReq(String code, String name, Long parentId,
                                              String currency, BigDecimal ownershipPct,
                                              boolean isParent) {
        LegalEntityCreateRequest req = new LegalEntityCreateRequest();
        req.setEntityCode(code);
        req.setEntityName(name);
        req.setParentId(parentId);
        req.setCurrency(currency);
        req.setOwnershipPct(ownershipPct);
        req.setIsParent(isParent);
        return req;
    }
}
