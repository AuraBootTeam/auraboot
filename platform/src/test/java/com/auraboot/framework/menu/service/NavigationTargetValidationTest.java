package com.auraboot.framework.menu.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class NavigationTargetValidationTest {
    private static com.auraboot.framework.menu.dto.NavigationConfiguration.Definition directory(String parent) {
        return new com.auraboot.framework.menu.dto.NavigationConfiguration.Definition("Directory",parent,"Folder","directory","","same",null,true,true,true,10);
    }
    @org.junit.jupiter.api.Test
    void cannotMoveAParentUnderItsOwnDescendant() {
        var definitions=java.util.Map.of("parent",directory(null),"child",directory("parent"));
        assertThrows(RuntimeException.class,()->NavigationConfigurationService.validateTopology("parent",directory("child"),definitions));
    }
    @org.junit.jupiter.api.Test
    void cannotChangeADirectoryWithChildrenIntoALink() {
        var link=new com.auraboot.framework.menu.dto.NavigationConfiguration.Definition("Link",null,"Link","internal","/home","same",null,true,true,true,10);
        assertThrows(RuntimeException.class,()->NavigationConfigurationService.validateTopology("parent",link,java.util.Map.of("child",directory("parent"))));
    }
    @org.junit.jupiter.api.Test
    void canMoveAChildToAnotherDirectoryWithoutChangingItsAccessRule() {
        var entries=java.util.Map.of("first",directory(null),"second",directory(null),"child",directory("first"));
        assertDoesNotThrow(()->NavigationConfigurationService.validateTopology("child",directory("second"),entries));
    }

    @org.junit.jupiter.api.Test
    void customNavigationCannotReplaceTheExistingPageAccessRequirement() {
        var custom=new com.auraboot.framework.menu.entity.Menu();
        custom.setPath("/p/bom_list");
        var extension=new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setDynamicProperty("navigationCustom",true);
        custom.setExtension(extension);
        var source=new com.auraboot.framework.menu.entity.Menu();
        source.setPath("/engineering/bom");source.setPageKey("bom_list");
        source.setPermissionCode("bom.view");source.setVisible(false);
        var menus=java.util.List.of(custom,source);
        assertSame(source,NavigationConfigurationService.selectTargetAccessMenu(menus,"/p/bom_list"));
        assertSame(source,NavigationConfigurationService.selectTargetAccessMenu(menus,"/p/c/bom_list"));
        assertSame(source,NavigationConfigurationService.selectTargetAccessMenu(menus,"/engineering/bom"));
        assertNull(NavigationConfigurationService.selectTargetAccessMenu(java.util.List.of(custom),"/p/bom_list"));
    }

    @org.junit.jupiter.api.Test
    void scopedLegacyLeavesInheritTheExistingDirectoryRequirementWithoutOverridingTheirOwn(){
        var root=new com.auraboot.framework.menu.entity.Menu();root.setId(1L);root.setPermissionCode("ai_center");
        var settings=new com.auraboot.framework.menu.entity.Menu();settings.setId(2L);settings.setParentId(1L);
        var leaf=new com.auraboot.framework.menu.entity.Menu();leaf.setId(3L);leaf.setParentId(2L);
        var menus=java.util.Map.of(1L,root,2L,settings,3L,leaf);
        assertFalse(RoleNavigationService.legacyAllowed(leaf,java.util.Set.of("bom.cap.write"),menus,true));
        assertTrue(RoleNavigationService.legacyAllowed(leaf,java.util.Set.of("ai_center"),menus,true));
        assertTrue(RoleNavigationService.legacyAllowed(leaf,java.util.Set.of(),menus,false));
        leaf.setPermissionCode("audit.read");
        assertTrue(RoleNavigationService.legacyAllowed(leaf,java.util.Set.of("audit.read"),menus,true));
        assertFalse(RoleNavigationService.legacyAllowed(leaf,java.util.Set.of("ai_center"),menus,true));
    }

    @org.junit.jupiter.api.Test
    void scopedLegacyPublicRootsRemainPublicAndUnqualifiedCyclesFailClosed(){
        var publicLeaf=new com.auraboot.framework.menu.entity.Menu();publicLeaf.setId(1L);
        assertTrue(RoleNavigationService.legacyAllowed(publicLeaf,java.util.Set.of(),java.util.Map.of(1L,publicLeaf),true));
        publicLeaf.setParentId(1L);
        assertFalse(RoleNavigationService.legacyAllowed(publicLeaf,java.util.Set.of(),java.util.Map.of(1L,publicLeaf),true));
    }

    @ParameterizedTest
    @CsvSource({"external,https://example.com/docs?tab=guide", "embedded,http://localhost:8000/guide", "internal,/p/qo_quote_common?status=draft", "page,qo_quote_common_list"})
    void acceptsSupportedNavigationTargets(String kind,String target) {
        assertDoesNotThrow(() -> NavigationConfigurationService.validateTarget(kind,target));
    }
    @ParameterizedTest
    @CsvSource({"external,javascript:alert(1)","external,//example.com/docs","embedded,data:text/html;test","external,https://user:secret@example.com","internal,//example.com","internal,/a/../api","internal,https://example.com","page,../other"})
    void rejectsTargetsThatChangeTheDeclaredNavigationBoundary(String kind,String target) {
        assertThrows(RuntimeException.class,() -> NavigationConfigurationService.validateTarget(kind,target));
    }
}
