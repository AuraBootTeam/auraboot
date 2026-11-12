package com.auraboot.framework.application.tenant;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationCatalogPolicyTest {
    private final TenantPreferenceService preferences=mock(TenantPreferenceService.class);
    private final ApplicationCatalogPolicy policy=new ApplicationCatalogPolicy(preferences,"bom-quote");
    private final ObjectMapper json=new ObjectMapper();

    @AfterEach void clear() { MetaContext.clear(); }

    private ObjectNode declaration() {
        var value=json.createObjectNode();value.put("version",1).put("applicationCode","bom-quote");
        value.putArray("menuCodes").add("current");
        value.putArray("capabilityCodes").add("current.cap.read");
        value.putArray("pageKeys").add("current_list");
        return value;
    }

    @Test void absencePreservesExistingProductsWithoutChangingTheirCatalog() {
        var all=List.of(menu(1L,null,"unrelated"));
        assertThat(policy.read(42L).active()).isFalse();
        assertThat(policy.read(42L).menus(all)).isSameAs(all);
    }

    @Test void foreignTenantIsRejectedBeforeAStoreRead() {
        MetaContext.setContext(41L,1L,"user","User");
        assertThatThrownBy(()->policy.read(42L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(preferences);
    }

    @ParameterizedTest
    @ValueSource(strings={"missing","extra","version","hugeVersion","fraction","application","notArray","duplicate","blank","nonText","null"})
    void malformedStoredCatalogDoesNotFallBackToTheWholeProduct(String variant) {
        var value=declaration();
        switch(variant) {
            case "missing" -> value.remove("pageKeys");
            case "extra" -> value.put("permissions",true);
            case "version" -> value.put("version",2);
            case "hugeVersion" -> value.put("version",new java.math.BigInteger("18446744073709551617"));
            case "fraction" -> value.put("version",1.5);
            case "application" -> value.put("applicationCode","");
            case "notArray" -> value.put("menuCodes","current");
            case "duplicate" -> value.withArray("menuCodes").add("current");
            case "blank" -> value.withArray("capabilityCodes").add(" ");
            case "nonText" -> value.withArray("pageKeys").add(2);
            case "null" -> value=null;
        }
        when(preferences.getPreference(42L,ApplicationCatalogPolicy.key("bom-quote")))
                .thenReturn(value==null?json.nullNode():value);
        assertThatThrownBy(()->policy.read(42L)).isInstanceOf(IllegalStateException.class);
    }

    @Test void customEntriesKeepTheirAncestorsAndLeaveStoredMenusUnchanged() {
        var root=menu(1L,null,"old_root");var current=menu(2L,null,"current");
        var custom=menu(3L,1L,"custom");var extension=new ExtensionBean();
        extension.setDynamicProperty("navigationCustom",true);custom.setExtension(extension);
        var hidden=menu(4L,1L,"old_child");var all=List.of(root,current,custom,hidden);
        var scope=ApplicationCatalogPolicy.parse(declaration());
        assertThat(scope.menus(all)).containsExactly(root,current,custom);
        assertThat(all).hasSize(4);assertThat(custom.getParentId()).isEqualTo(1L);
        assertThat(hidden.getVisible()).isTrue();
        assertThat(scope.page("current_list")).isTrue();assertThat(scope.page("old_list")).isFalse();
    }

    @Test void aRetainedDefaultProjectsToRootWithoutChangingTheSharedStoredParent() {
        var outside=menu(1L,null,"outside");var current=menu(2L,1L,"current");
        var projected=ApplicationCatalogPolicy.parse(declaration()).menus(List.of(outside,current));
        assertThat(projected).singleElement().satisfies(menu->{
            assertThat(menu.getPid()).isEqualTo(current.getPid());
            assertThat(menu.getParentId()).isNull();
            assertThat(menu).isNotSameAs(current);
        });
        assertThat(current.getParentId()).isEqualTo(1L);
    }

    @Test void aCustomChildPreservesTheWholeOriginalAncestorChain() {
        var outside=menu(1L,null,"outside");var current=menu(2L,1L,"current");var custom=menu(3L,2L,"custom");
        var extension=new ExtensionBean();extension.setDynamicProperty("navigationCustom",true);custom.setExtension(extension);
        var all=List.of(outside,current,custom);
        assertThat(ApplicationCatalogPolicy.parse(declaration()).menus(all)).containsExactlyElementsOf(all);
        assertThat(current.getParentId()).isEqualTo(1L);assertThat(custom.getParentId()).isEqualTo(2L);
    }

    @Test void catalogPreferenceWritesSerializeBeforeAccessingStoredPreferences() {
        var mapper=mock(com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper.class);
        var lock=mock(com.auraboot.framework.permission.service.RoleAuthorizationWriteLock.class);
        var service=new com.auraboot.framework.tenant.service.impl.TenantPreferenceServiceImpl(mapper,lock);
        service.setPreference(42L,ApplicationCatalogPolicy.key("bom-quote"),declaration());
        var order=inOrder(lock,mapper);
        order.verify(lock).mutateForTenant(42L);
        order.verify(mapper).selectOne(any());
        order.verify(mapper).insert(any(com.auraboot.framework.tenant.dao.entity.TenantPreference.class));
    }

    @Test void malformedPreferenceWriteDoesNotLockOrWriteTheStore() {
        var mapper=mock(com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper.class);
        var lock=mock(com.auraboot.framework.permission.service.RoleAuthorizationWriteLock.class);
        var service=new com.auraboot.framework.tenant.service.impl.TenantPreferenceServiceImpl(mapper,lock);
        assertThatThrownBy(()->service.setPreference(42L,ApplicationCatalogPolicy.key("bom-quote"),json.nullNode()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(lock,mapper);
    }

    @Test void anotherProductUsesItsOwnPreferenceKeyInTheSameTenant() {
        when(preferences.getPreference(42L,ApplicationCatalogPolicy.key("bom-quote"))).thenReturn(declaration());
        assertThat(new ApplicationCatalogPolicy(preferences,"other-product").read(42L).active()).isFalse();
        verify(preferences).getPreference(42L,ApplicationCatalogPolicy.key("other-product"));
        verify(preferences,never()).getPreference(42L,ApplicationCatalogPolicy.key("bom-quote"));
    }

    @Test void unconfiguredRuntimesDoNotReadAnotherProductsCatalog() {
        assertThat(new ApplicationCatalogPolicy(preferences,"").read(42L).active()).isFalse();
        verifyNoInteractions(preferences);
    }

    @Test void preferenceKeyCannotPublishAnotherApplicationIdentity() {
        var mapper=mock(com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper.class);
        var lock=mock(com.auraboot.framework.permission.service.RoleAuthorizationWriteLock.class);
        var service=new com.auraboot.framework.tenant.service.impl.TenantPreferenceServiceImpl(mapper,lock);
        assertThatThrownBy(()->service.setPreference(42L,ApplicationCatalogPolicy.key("other-product"),declaration()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(lock,mapper);
    }

    private Menu menu(Long id,Long parent,String code) {
        var menu=new Menu();menu.setId(id);menu.setPid(code);menu.setCode(code);menu.setParentId(parent);menu.setVisible(true);
        return menu;
    }
}
