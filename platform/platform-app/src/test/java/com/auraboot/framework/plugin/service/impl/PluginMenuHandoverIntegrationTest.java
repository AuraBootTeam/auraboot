package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginManagerService;
import com.auraboot.framework.plugin.service.PluginResourceService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Public import and lifecycle services with actual PostgreSQL menu/ownership rows. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginMenuHandoverIntegrationTest extends BaseIntegrationTest {
    @Autowired PluginImportService imports;
    @Autowired PluginManagerService lifecycle;
    @Autowired PluginResourceService resources;
    @Autowired PluginResourceTracker tracker;
    @Autowired MenuService menus;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sourceReimportAndExitPreserveClaimedAndModifiedMenus() {
        String key = key();
        var source = manifest("source_" + key, menu(key + "_claimed"), menu(key + "_modified"), menu(key + "_plain"));
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        resources.claimByUser(tenant(), ResourceType.MENU, key + "_claimed");
        customize(key + "_modified");
        String claimedPid = menuPid(key + "_claimed");
        String modifiedPid = menuPid(key + "_modified");
        install(manifest("source_" + key), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertProtected(key, claimedPid, modifiedPid);
        assertThat(activeCount(key + "_plain")).isZero();
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertProtected(key, claimedPid, modifiedPid);
    }

    @Test
    void directUninstallPreservesProtectedMenusAndRemovesOrdinaryMenus() {
        String key = key();
        var source = manifest("exit_" + key, menu(key + "_claimed"), menu(key + "_modified"), menu(key + "_plain"));
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        resources.claimByUser(tenant(), ResourceType.MENU, key + "_claimed");
        customize(key + "_modified");
        String claimedPid = menuPid(key + "_claimed");
        String modifiedPid = menuPid(key + "_modified");
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertProtected(key, claimedPid, modifiedPid);
        assertThat(activeCount(key + "_plain")).isZero();
    }

    @Test
    void explicitHandoverSurvivesOldSourceReimportDisableAndUninstall() {
        String key = key();
        var original = menu(key);
        var source = manifest("old_" + key, original);
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        String pid = menuPid(key);
        var received = menu(key); received.setName("Receiver menu");
        var receiver = manifest("new_" + key, received);
        var adopted = install(receiver, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
        assertThat(menuPid(key)).isEqualTo(pid);
        // An old release still declaring the same code must not silently reclaim it.
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
        assertThat(menuName(key)).isEqualTo("Receiver menu");
        install(manifest("old_" + key), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(lifecycle.enable(source.getPluginId()).isSuccess()).isTrue();
        assertThat(lifecycle.disable(source.getPluginId()).isSuccess()).isTrue();
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertThat(activeCount(key)).isEqualTo(1);
        assertThat(menuPid(key)).isEqualTo(pid);
        assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='menu' AND resource_code=?", Integer.class, tenant(), key)).isEqualTo(1);
        install(receiver, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
    }

    @Test
    void safeReceiverSkipKeepsCustomerBaselineAndOldSourceCleanupCannotDeleteIt() {
        String key = key();
        var source = manifest("protected_" + key, menu(key));
        var first = install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        customize(key);
        String snapshot = jdbc.queryForObject("SELECT import_snapshot::text FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='menu' AND resource_code=?", String.class, tenant(), key);
        install(manifest("receiver_" + key, menu(key)), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(owner(key)).isEqualTo(first.getPluginPid());
        assertThat(menuName(key)).isEqualTo("Customer menu");
        assertThat(jdbc.queryForObject("SELECT import_snapshot::text FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='menu' AND resource_code=?", String.class, tenant(), key)).isEqualTo(snapshot);
        install(manifest("protected_" + key), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertThat(activeCount(key)).isEqualTo(1);
        assertThat(menuName(key)).isEqualTo("Customer menu");
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.MENU, key).getUserModified()).isTrue();
    }

    @Test
    void samePluginSafeReimportUpdatesUnmodifiedMenuWithoutChangingIdentity() {
        String key = key();
        var source = manifest("same_" + key, menu(key));
        var installed = install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        String pid = menuPid(key);
        source.getMenus().getFirst().setName("Updated by same owner");
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(menuPid(key)).isEqualTo(pid);
        assertThat(menuName(key)).isEqualTo("Updated by same owner");
        assertThat(owner(key)).isEqualTo(installed.getPluginPid());
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.MENU, key).getUserModified()).isFalse();
    }

    @Test
    void authoringAndUnknownProtectionMenusSurviveWhileOtherTenantIsUntouched() {
        String key = key();
        var authored = menu(key + "_authored"); authored.setExtension(Map.of("authoringManaged", true));
        var source = manifest("bounds_" + key, authored, menu(key + "_unknown"), menu(key + "_plain"));
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=NULL WHERE tenant_id=? AND resource_type='menu' AND resource_code=?", tenant(), key + "_unknown");
        long originalTenant = tenant();
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        Long memberId = MetaContext.getCurrentMemberId();
        long other = System.nanoTime();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)", other, UniqueIdGenerator.generate(), "Menu boundary tenant");
        try {
            MetaContext.setContext(other, userId, userPid, "Menu boundary reviewer");
            install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        } finally {
            MetaContext.setContext(originalTenant, userId, userPid, "Menu boundary reviewer");
            MetaContext.setMemberId(memberId);
        }
        install(manifest("bounds_" + key), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(activeCount(key + "_authored")).isEqualTo(1);
        assertThat(activeCount(key + "_unknown")).isEqualTo(1);
        assertThat(activeCount(key + "_plain")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_menu WHERE tenant_id=? AND code IN (?,?,?) AND deleted_flag=false", Integer.class, other, key + "_authored", key + "_unknown", key + "_plain")).isEqualTo(3);
    }

    @Test
    void protectedChildRejectsParentPruneWithoutDeletingEitherMenu() {
        String key = key();
        var parent = menu(key + "_parent"); parent.setType(0);
        var child = menu(key + "_child"); child.setParentCode(parent.getCode());
        var source = manifest("tree_" + key, parent, child);
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        resources.claimByUser(tenant(), ResourceType.MENU, child.getCode());
        assertThatThrownBy(() -> install(manifest("tree_" + key), ImportRequest.ConflictStrategy.OVERWRITE_SAFE))
                .hasMessageContaining("retained child");
        assertThat(activeCount(parent.getCode())).isEqualTo(1);
        assertThat(activeCount(child.getCode())).isEqualTo(1);
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.MENU, child.getCode()).getOwnershipType()).isEqualTo("user_claimed");
    }

    private String key() { return "mh_" + UniqueIdGenerator.generate().toLowerCase(); }
    private long tenant() { return MetaContext.getCurrentTenantId(); }
    private MenuDefinitionDTO menu(String code) {
        return MenuDefinitionDTO.builder().code(code).name("Original menu").type(1).path("/audit/" + code).visible(true).build();
    }
    private PluginManifestExtended manifest(String id, MenuDefinitionDTO... definitions) {
        return PluginManifestExtended.builder().pluginId("com.test." + id).namespace(id).version("1.0.0")
                .menus(List.of(definitions)).build();
    }
    private ImportExecuteResult install(PluginManifestExtended manifest, ImportRequest.ConflictStrategy strategy) {
        var request = new ImportRequest(); request.setConflictStrategy(strategy);
        var result = imports.executeFromManifest(manifest, request);
        assertThat(result.isSuccess()).as("public import result").isTrue(); return result;
    }
    private void customize(String code) {
        var menu = menus.getByPath(tenant(), "/audit/" + code);
        menu.setName("Customer menu"); menus.updateMenu(menu);
        tracker.markAsUserModified(ResourceType.MENU, code);
    }
    private int activeCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM ab_menu WHERE tenant_id=? AND code=? AND deleted_flag=false", Integer.class, tenant(), code);
    }
    private String menuPid(String code) {
        return jdbc.queryForObject("SELECT pid FROM ab_menu WHERE tenant_id=? AND code=? AND deleted_flag=false", String.class, tenant(), code);
    }
    private String menuName(String code) {
        return jdbc.queryForObject("SELECT name FROM ab_menu WHERE tenant_id=? AND code=? AND deleted_flag=false", String.class, tenant(), code);
    }
    private String owner(String code) {
        return resources.findByTypeAndCode(tenant(), ResourceType.MENU, code).getPluginPid();
    }
    private void assertProtected(String key, String claimedPid, String modifiedPid) {
        assertThat(menuPid(key + "_claimed")).isEqualTo(claimedPid);
        assertThat(menuPid(key + "_modified")).isEqualTo(modifiedPid);
        assertThat(menuName(key + "_modified")).isEqualTo("Customer menu");
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.MENU, key + "_claimed").getOwnershipType()).isEqualTo("user_claimed");
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.MENU, key + "_modified").getUserModified()).isTrue();
    }
}
