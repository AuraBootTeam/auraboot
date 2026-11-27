package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestProjectionDataAccessorTest {
    private final DataAccessor ordinary=mock(DataAccessor.class);
    private final TenantProjectionAccessor projection=mock(TenantProjectionAccessor.class);
    private final AtomicBoolean authorized=new AtomicBoolean(true);
    @BeforeEach void identity(){MetaContext.setContext(7L,17L,"buyer","Buyer");MetaContext.setMemberId(27L);}
    @AfterEach void clear(){MetaContext.clear();}
    private RestProjectionDataAccessor accessor(){return new RestProjectionDataAccessor(ordinary,projection,Set.of("owned_quote"),authorized::get);}
    @Test void declaredReadsUseOnlyTheProjectionAndOtherModelsKeepOrdinaryAuthorization(){
        var expected=Map.<String,Object>of("pid","quote");when(projection.getById("owned_quote","quote")).thenReturn(expected);
        var data=accessor();assertSame(expected,data.getById("owned_quote","quote"));verifyNoInteractions(ordinary);
        when(ordinary.getById("scoped_task","task")).thenThrow(new AccessDeniedException("Record scope denied"));
        assertThrows(AccessDeniedException.class,()->data.getById("scoped_task","task"));verify(projection,never()).getById("scoped_task","task");
    }
    @Test void pagedAndFilteredQueriesKeepTheirExactArguments(){
        var data=accessor();var query=new DataPageQuery(Map.of("task","task-a"),Map.of(),List.of(new DataPageQuery.Sort("pid",false)),2,20);
        var page=new DataPage(List.of(Map.of("pid","q")),21,2,20);when(projection.queryPage("owned_quote",query)).thenReturn(page);
        assertSame(page,data.queryPage("owned_quote",query));data.query("owned_quote",Map.of("task","task-a"));data.queryIn("scoped_task","pid",List.of("task-a"));
        verify(projection).query("owned_quote",Map.of("task","task-a"));verify(ordinary).queryIn("scoped_task","pid",List.of("task-a"));
    }
    @Test void identityChangesAndRevokedRoutePermissionFailBeforeEitherDelegate(){
        var data=accessor();MetaContext.setMemberId(28L);assertThrows(AccessDeniedException.class,()->data.getById("owned_quote","quote"));
        identity();MetaContext.setContext(8L,17L,"buyer","Buyer");MetaContext.setMemberId(27L);assertThrows(AccessDeniedException.class,()->data.query("owned_quote",Map.of()));
        identity();authorized.set(false);assertThrows(AccessDeniedException.class,()->data.queryPage("owned_quote",new DataPageQuery(Map.of(),Map.of(),List.of(),1,20)));
        verifyNoInteractions(ordinary,projection);
    }
    @Test void missingHumanMembershipAndEveryNativeWriteAreRejected(){
        MetaContext.clearMemberId();assertThrows(AccessDeniedException.class,this::accessor);identity();var data=accessor();
        assertThrows(AccessDeniedException.class,()->data.create("owned_quote",Map.of()));
        assertThrows(AccessDeniedException.class,()->data.update("owned_quote","quote",Map.of()));
        assertThrows(AccessDeniedException.class,()->data.batchCreate("scoped_task",List.of(Map.of())));
        assertThrows(AccessDeniedException.class,()->data.delete("owned_quote","quote"));
        verifyNoInteractions(ordinary,projection);
    }
}
