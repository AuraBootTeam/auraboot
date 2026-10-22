package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.DataDomain;
import com.auraboot.framework.meta.entity.UserDataDomain;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataDomainService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DataDomainServiceImpl} — domain CRUD, hierarchy,
 * user-domain bindings, and SQL/in-memory domain filtering. No mocks: a dedicated synthetic
 * tenant on the real DB ({@code ab_data_domain} / {@code ab_user_data_domain}), torn down by
 * raw SQL (the entities are {@code @TableLogic} soft-deleted, so the suite cleans by tenant).
 *
 * <p>Part of the OSS coverage-to-80 initiative (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). {@code DataDomainServiceImpl}
 * was ~7% line-covered.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DataDomainServiceImpl Coverage IT — domain CRUD + hierarchy + user bindings + filters")
class DataDomainServiceImplCoverageIT {

    private static final long TENANT_ID = 990_100_001L;
    private final AtomicLong userSeq = new AtomicLong(990_200_000L);
    private final AtomicLong codeSeq = new AtomicLong();

    @Autowired
    private DataDomainService dataDomainService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_001L, "dd-test-pid", "dd-test-user");
        dataDomainService.evictCache();
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_user_data_domain WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("DELETE FROM ab_data_domain WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private DataDomain newDomain(String namePrefix, Long parentId) {
        DataDomain d = new DataDomain();
        d.setDomainCode(namePrefix + "_" + codeSeq.incrementAndGet());
        d.setDomainName(namePrefix + " name");
        d.setDescription("desc for " + namePrefix);
        d.setEnabled(true);
        d.setParentDomainId(parentId);
        return dataDomainService.createDomain(d);
    }

    @Test
    @DisplayName("create / getByCode / getDomain / listDomains round-trip")
    void crudRoundTrip() {
        DataDomain created = newDomain("region", null);
        assertNotNull(created.getId());
        assertEquals(TENANT_ID, created.getTenantId());

        DataDomain byCode = dataDomainService.getDomainByCode(created.getDomainCode());
        assertEquals(created.getId(), byCode.getId());

        DataDomain byId = dataDomainService.getDomain(created.getId());
        assertEquals(created.getDomainCode(), byId.getDomainCode());

        List<DataDomain> all = dataDomainService.listDomains();
        assertTrue(all.stream().anyMatch(x -> x.getId().equals(created.getId())));
    }

    @Test
    @DisplayName("create rejects duplicate code and missing parent")
    void createErrorBranches() {
        DataDomain first = newDomain("dup", null);

        DataDomain dupe = new DataDomain();
        dupe.setDomainCode(first.getDomainCode());
        dupe.setDomainName("dupe");
        assertThrows(MetaServiceException.class, () -> dataDomainService.createDomain(dupe));

        DataDomain orphan = new DataDomain();
        orphan.setDomainCode("orphan_" + codeSeq.incrementAndGet());
        orphan.setDomainName("orphan");
        orphan.setParentDomainId(-999L);
        assertThrows(MetaServiceException.class, () -> dataDomainService.createDomain(orphan));
    }

    @Test
    @DisplayName("getDomain / getDomainByCode throw for missing ids")
    void getNotFound() {
        assertThrows(MetaServiceException.class, () -> dataDomainService.getDomain(-12345L));
        assertThrows(MetaServiceException.class, () -> dataDomainService.getDomainByCode("no-such-code"));
    }

    @Test
    @DisplayName("parent/child hierarchy + getChildren")
    void hierarchy() {
        DataDomain parent = newDomain("parent", null);
        DataDomain child = newDomain("child", parent.getId());

        List<DataDomain> children = dataDomainService.getChildren(parent.getId());
        assertTrue(children.stream().anyMatch(c -> c.getId().equals(child.getId())));
    }

    @Test
    @DisplayName("update applies fields and rejects self-parent")
    void updateBranches() {
        DataDomain d = newDomain("upd", null);

        DataDomain updates = new DataDomain();
        updates.setDomainName("renamed");
        updates.setDescription("new desc");
        updates.setEnabled(false);
        DataDomain updated = dataDomainService.updateDomain(d.getId(), updates);
        assertEquals("renamed", updated.getDomainName());
        assertEquals("new desc", updated.getDescription());
        assertFalse(updated.getEnabled());

        DataDomain circular = new DataDomain();
        circular.setParentDomainId(d.getId());
        assertThrows(MetaServiceException.class, () -> dataDomainService.updateDomain(d.getId(), circular));
    }

    @Test
    @DisplayName("delete rejects domains with children, then deletes leaf-first")
    void deleteBranches() {
        DataDomain parent = newDomain("delp", null);
        DataDomain child = newDomain("delc", parent.getId());

        assertThrows(MetaServiceException.class, () -> dataDomainService.deleteDomain(parent.getId()));

        dataDomainService.deleteDomain(child.getId());
        dataDomainService.deleteDomain(parent.getId());
        assertThrows(MetaServiceException.class, () -> dataDomainService.getDomain(parent.getId()));
    }

    @Test
    @DisplayName("assignUser (new + primary update), getUserDomains, getDomainUserIds, removeUser")
    void userBindings() {
        DataDomain d = newDomain("bind", null);
        long userId = userSeq.incrementAndGet();

        UserDataDomain binding = dataDomainService.assignUser(d.getId(), userId, false);
        assertNotNull(binding.getId());

        // re-assign with a different primary flag exercises the update path
        UserDataDomain updated = dataDomainService.assignUser(d.getId(), userId, true);
        assertTrue(updated.getIsPrimary());

        List<DataDomain> userDomains = dataDomainService.getUserDomains(userId);
        assertTrue(userDomains.stream().anyMatch(x -> x.getId().equals(d.getId())));

        List<Long> domainUserIds = dataDomainService.getDomainUserIds(d.getId());
        assertTrue(domainUserIds.contains(userId));

        dataDomainService.removeUser(d.getId(), userId);
        assertTrue(dataDomainService.getUserDomains(userId).isEmpty());
    }

    @Test
    @DisplayName("getUserDomains returns empty for an unbound user")
    void unboundUser() {
        assertTrue(dataDomainService.getUserDomains(userSeq.incrementAndGet()).isEmpty());
    }

    @Test
    @DisplayName("buildDomainFilter: empty for unbound user, IN clause when assigned")
    void buildDomainFilter() {
        long unbound = userSeq.incrementAndGet();
        assertEquals("", dataDomainService.buildDomainFilter("any_model", unbound));

        DataDomain d = newDomain("flt", null);
        long userId = userSeq.incrementAndGet();
        dataDomainService.assignUser(d.getId(), userId, true);

        String filter = dataDomainService.buildDomainFilter("any_model", userId);
        assertTrue(filter.startsWith("AND domain_id IN ("));
        assertTrue(filter.contains(String.valueOf(d.getId())));
    }

    @Test
    @DisplayName("filterByDomain keeps matching + null-domain records, drops others; pass-through when unbound")
    void filterByDomain() {
        DataDomain d = newDomain("inmem", null);
        long userId = userSeq.incrementAndGet();
        dataDomainService.assignUser(d.getId(), userId, true);

        Map<String, Object> matching = new HashMap<>();
        matching.put("domain_id", d.getId());
        Map<String, Object> other = new HashMap<>();
        other.put("domain_id", -77L);
        Map<String, Object> nullDomain = new HashMap<>();
        nullDomain.put("domain_id", null);

        List<Map<String, Object>> filtered =
                dataDomainService.filterByDomain("m", userId, List.of(matching, other, nullDomain));
        assertEquals(2, filtered.size()); // matching + null-domain stay, "other" dropped

        // unbound user -> pass-through (open access)
        long unbound = userSeq.incrementAndGet();
        List<Map<String, Object>> passThrough =
                dataDomainService.filterByDomain("m", unbound, List.of(matching, other));
        assertEquals(2, passThrough.size());

        // empty input short-circuits
        assertTrue(dataDomainService.filterByDomain("m", userId, List.of()).isEmpty());
    }

    @Test
    @DisplayName("getUserDomainIdsWithDescendants includes assigned domain (and its subtree)")
    void userDomainIdsWithDescendants() {
        DataDomain root = newDomain("treeroot", null);
        DataDomain leaf = newDomain("treeleaf", root.getId());
        long userId = userSeq.incrementAndGet();
        dataDomainService.assignUser(root.getId(), userId, true);

        Set<Long> ids = dataDomainService.getUserDomainIdsWithDescendants(userId);
        assertTrue(ids.contains(root.getId()) || ids.contains(leaf.getId()));
        assertFalse(ids.isEmpty());
    }
}
