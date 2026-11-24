package com.auraboot.framework.menu.service;

import com.auraboot.framework.tenant.dto.UserSpaceDTO;
import com.auraboot.framework.tenant.service.UserSpaceService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlatformManagementNavigationTest {
    private final UserSpaceService spaces=mock(UserSpaceService.class);
    private final PlatformManagementNavigation navigation=new PlatformManagementNavigation(spaces);
    @Test void nonPlatformMemberGetsOnlyOpenPlatformAndNoSpaceSwitch() {
        when(spaces.listForUser(7L)).thenReturn(List.of(UserSpaceDTO.builder().spaceType("business").roleCodes(List.of("tenant_admin")).build()));
        var group=navigation.append(List.of(),7L,9L).getFirst();
        assertThat(group.getI18nKey()).isEqualTo("sidebar.platformManagement");
        assertThat(group.getChildren()).hasSize(1);
        assertThat(group.getChildren().getFirst().getChildren()).extracting(item->item.getPath())
                .containsExactly("/settings/api-docs?view=applications","/settings/api-docs?view=calls","/settings/api-docs?view=webhooks");
    }
    @Test void platformRoleGetsAnExplicitPostSwitchNavigationMarker() {
        when(spaces.listForUser(7L)).thenReturn(List.of(UserSpaceDTO.builder().spaceType("platform").roleCodes(List.of("platform_admin")).build()));
        var children=navigation.append(List.of(),7L,9L).getFirst().getChildren();
        assertThat(children).hasSize(2);
        assertThat(children.getFirst().getPath()).isEqualTo("/platform/plugins");
        assertThat(children.getFirst().getExtension().get("navigationOpening")).isEqualTo("platform");
    }
    @Test void aSystemSpaceWithoutPlatformRoleDoesNotGrantConsoleAccess() {
        when(spaces.listForUser(7L)).thenReturn(List.of(UserSpaceDTO.builder().spaceType("platform").roleCodes(List.of("viewer")).build()));
        assertThat(navigation.append(List.of(),7L,9L).getFirst().getChildren()).hasSize(1);
    }
    @Test void tenantSelectionStageHasNoPlatformNavigation() {
        assertThat(navigation.append(List.of(),7L,null)).isEmpty();verifyNoInteractions(spaces);
    }
}
