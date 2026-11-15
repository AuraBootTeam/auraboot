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
}
