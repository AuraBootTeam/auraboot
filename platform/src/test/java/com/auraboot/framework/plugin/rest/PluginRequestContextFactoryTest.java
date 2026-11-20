package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginRequestContextFactoryTest {
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void resolvesDisplayIdentityUsingOnlyTheCapturedTenant() {
        MetaContext.setContext(7L,17L,"operator","Operator");
        var clock=mock(TenantClock.class);when(clock.getZoneId(7L)).thenReturn(ZoneOffset.UTC);
        var directory=mock(IdentityDirectoryAccessor.class);
        var expected=new IdentityDirectoryAccessor.UserIdentity(23L,"owner","owner-login","Owner name");
        when(directory.findActiveTenantUser(7L,"owner")).thenReturn(expected);
        var context=new PluginRequestContextFactory(clock,mock(DynamicDataService.class),directory).current(false);
        assertSame(expected,context.findActiveTenantUser("owner"));
        assertNull(context.findActiveTenantUser("foreign"));
        verify(directory).findActiveTenantUser(7L,"owner");verify(directory).findActiveTenantUser(7L,"foreign");
        verify(directory,never()).findUser(anyString());
    }
    @Test void publicAnonymousAndMissingIdentitiesCannotQueryTheDirectory() {
        var clock=mock(TenantClock.class);when(clock.getZoneId(7L)).thenReturn(ZoneOffset.UTC);
        var directory=mock(IdentityDirectoryAccessor.class);
        var factory=new PluginRequestContextFactory(clock,mock(DynamicDataService.class),directory);
        MetaContext.setContext(7L,17L,"operator","Operator");
        assertNull(factory.current(true).findActiveTenantUser("owner"));
        assertNull(factory.current(false).findActiveTenantUser(""));
        MetaContext.clear();assertThrows(IllegalStateException.class,()->factory.current(false));
        MetaContext.setContext(7L,0L,"public","Public");assertNull(factory.current(false).findActiveTenantUser("owner"));
        verifyNoInteractions(directory);
    }
    @Test void projectionOptInIsLimitedToAuthenticatedReadOnlyGetRoutes() {
        MetaContext.setContext(7L,17L,"operator","Operator");MetaContext.setMemberId(27L);
        var clock=mock(TenantClock.class);when(clock.getZoneId(7L)).thenReturn(ZoneOffset.UTC);
        var permissions=mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        when(permissions.hasPermission(17L,"probe.review.read")).thenReturn(true);
        var factory=new PluginRequestContextFactory(clock,mock(DynamicDataService.class),mock(IdentityDirectoryAccessor.class),permissions);
        var route=new com.auraboot.framework.plugin.extension.RestRoute("GET","/review","probe.review.read",com.auraboot.framework.plugin.extension.AuthPolicy.AUTHENTICATED,true,true,null);
        assertNotNull(factory.current(false,route,java.util.Set.of("owned_quote")).dataAccessor());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->factory.current(true,route,java.util.Set.of("owned_quote")));
        for(var invalid:java.util.List.of(
                new com.auraboot.framework.plugin.extension.RestRoute("POST","/review","probe.review.read",com.auraboot.framework.plugin.extension.AuthPolicy.AUTHENTICATED,true,true,null),
                new com.auraboot.framework.plugin.extension.RestRoute("GET","/review","probe.review.read",com.auraboot.framework.plugin.extension.AuthPolicy.PUBLIC,true,true,null),
                new com.auraboot.framework.plugin.extension.RestRoute("GET","/review","probe.review.read",com.auraboot.framework.plugin.extension.AuthPolicy.AUTHENTICATED,true,false,null)))
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->factory.current(false,invalid,java.util.Set.of("owned_quote")));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->factory.current(false,route,java.util.Set.of()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->factory.current(false,route,java.util.Set.of("quote;select")));
        when(permissions.hasPermission(17L,"probe.review.read")).thenReturn(false);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->factory.current(false,route,java.util.Set.of("owned_quote")));
    }
}
