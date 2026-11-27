package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformConsoleMenuBootstrapTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MenuMapper menus = mock(MenuMapper.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test void communityDoesNotSeedOrReadEnterpriseConsoleMenus() throws Exception {
        var supported = PlatformConsoleMenuBootstrap.readSupported(json, new Resource[0]);
        assertThat(supported).isEmpty();
        new PlatformConsoleMenuBootstrap(jdbc, menus, supported).reconcile(1L, 42L);
        verifyNoInteractions(menus);
        verify(jdbc).queryForList(contains("m.plugin_pid IS NULL"), eq(1L), eq("platform_overview"), eq("/platform/overview"));
    }

    @Test void removesPhysicalRowsAndTheirNavigationBindingsRatherThanHidingThem() {
        when(jdbc.queryForList(anyString(), eq(1L), eq("platform_overview"), eq("/platform/overview")))
                .thenReturn(List.of(Map.of("id", 7L, "pid", "retired-overview")));
        new PlatformConsoleMenuBootstrap(jdbc, menus, Set.of()).reconcile(1L, 42L);
        verify(jdbc).update("DELETE FROM ab_role_navigation_menu WHERE tenant_id=? AND menu_pid=?", 1L, "retired-overview");
        verify(jdbc).update("DELETE FROM ab_navigation_customization WHERE tenant_id=? AND menu_pid=?", 1L, "retired-overview");
        verify(jdbc).update("DELETE FROM ab_menu WHERE tenant_id=? AND id=? AND plugin_pid IS NULL", 1L, 7L);
    }

    @Test void enterpriseDeclarationSeedsParentsBeforeChildrenWithoutTheRetiredMarketplace() {
        Set<String> supported = PlatformConsoleMenuBootstrap.DEFINITIONS.stream().map(PlatformConsoleMenuBootstrap.Definition::code).collect(Collectors.toSet());
        List<Menu> created = new ArrayList<>(); AtomicLong ids = new AtomicLong(10L);
        doAnswer(call -> { Menu menu = call.getArgument(0); menu.setId(ids.incrementAndGet()); created.add(menu); return 1; })
                .when(menus).insert(any(Menu.class));
        new PlatformConsoleMenuBootstrap(jdbc, menus, supported).reconcile(1L, 42L);
        assertThat(created).extracting(Menu::getCode).containsExactlyElementsOf(PlatformConsoleMenuBootstrap.DEFINITIONS.stream().map(PlatformConsoleMenuBootstrap.Definition::code).toList());
        assertThat(created).extracting(Menu::getCode).doesNotContain("platform_marketplace");
        Menu commerce = created.stream().filter(m -> m.getCode().equals("platform_commerce")).findFirst().orElseThrow();
        assertThat(created.stream().filter(m -> m.getCode().equals("platform_licenses")).findFirst().orElseThrow().getParentId()).isEqualTo(commerce.getId());
        verify(jdbc).queryForList(anyString(), eq(1L), eq("platform_marketplace"), eq("/platform/marketplace"));
        verify(jdbc, never()).queryForList(anyString(), eq(1L), eq("platform_overview"), any());
    }

    @Test void rejectsCrossTenantReconciliationBeforeAnyDatabaseMutation() {
        assertThatThrownBy(() -> new PlatformConsoleMenuBootstrap(jdbc, menus, Set.of()).reconcile(7L, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, menus);
    }

    @Test void declarationsRejectUnknownDuplicateOrParentlessEntries() {
        for (String codes : List.of("[\"platform_marketplace\"]", "[\"platform_overview\",\"platform_overview\"]", "[\"platform_licenses\"]"))
            assertThatThrownBy(() -> PlatformConsoleMenuBootstrap.readSupported(json,
                    new Resource[]{new ByteArrayResource(("{\"schemaVersion\":1,\"menuCodes\":"+codes+"}").getBytes(StandardCharsets.UTF_8))}))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test void validDeclarationIsReadAsDistributionData() throws Exception {
        assertThat(PlatformConsoleMenuBootstrap.readSupported(json, new Resource[]{new ByteArrayResource(
                "{\"schemaVersion\":1,\"menuCodes\":[\"platform_overview\"]}".getBytes(StandardCharsets.UTF_8))}))
                .containsExactly("platform_overview");
    }
}
