package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessOwnerDomainCommandAuthorizationTest {
    static final String CODE="example.order:document", BUYER="example.manage", SUPPLIER="example.supplier";
    final UserPermissionService permissions=mock(UserPermissionService.class);
    final DomainCommandAuthorization domains=mock(DomainCommandAuthorization.class);
    final RecordShareService shares=mock(RecordShareService.class);
    final ExtensionRegistry registry=mock(ExtensionRegistry.class);
    final CommandHandlerExtension handler=mock(CommandHandlerExtension.class,
            withSettings().extraInterfaces(BusinessResourceAuthorization.class));
    final BusinessResourceAuthorization owner=(BusinessResourceAuthorization)handler;
    final CommandAuthorizationPhase phase=new CommandAuthorizationPhase(permissions,shares,mock(ObjectProvider.class));
    final Map<String,Object> params=new HashMap<>(Map.of("targetRecordAction","update","targetRecordAuthorization","business","dslPersistence",false));
    final Map<String,Object> config=new HashMap<>(Map.of("type","custom","handler",CODE,"handlerParams",params,"permissions",List.of(BUYER,SUPPLIER)));
    CommandPipelineContext context;

    @BeforeEach void setup() {
        MetaContext.setContext(7L,42L,"actor","example");MetaContext.setMemberId(84L);
        ReflectionTestUtils.setField(phase,"domains",domains);
        var definition=new CommandDefinition();definition.setModelCode("example_order");
        context=CommandPipelineContext.builder().commandCode(CODE).command(definition)
                .tenantId(7L).userId(42L).request(new CommandExecuteRequest()).execConfig(config).build();
        when(domains.isDomain(BUYER)).thenReturn(true);
        when(domains.authorize(SUPPLIER)).thenReturn(null);
    }
    @AfterEach void cleanup(){MetaContext.clear();}
    void bindOwner() {
        ReflectionTestUtils.setField(phase,"extensionRegistry",registry);
        when(registry.getBusinessResourceOwner(CODE)).thenReturn(Optional.of(handler));
        when(owner.businessBoundary()).thenReturn(new BusinessResourceAuthorization.Boundary(CODE,"example_order","update",Set.of(BUYER,SUPPLIER)));
        when(owner.businessActorAuthorization()).thenReturn(mock(BusinessActorAuthorization.class));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void exactBusinessOwnerCanUseOneOfItsDeclaredDutiesWithoutGrantingTheOther(boolean buyer) {
        bindOwner();when(domains.authorize(BUYER)).thenReturn(buyer);
        if(!buyer)when(permissions.hasPermission(42L,SUPPLIER)).thenReturn(true);
        phase.execute(context);
        assertThat(context.getAuthorizationVerdict().permissionCode()).isEqualTo(buyer?BUYER:SUPPLIER);
        verifyNoInteractions(shares);
    }
    @Test void ordinaryMultiPermissionDomainCommandStillFailsBeforeAnyPermissionFallback() {
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(permissions,shares);
    }
    @Test void validOwnerDoesNotGrantAnyDutyToAnUnprivilegedCaller() {
        bindOwner();when(domains.authorize(BUYER)).thenReturn(false);
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(BusinessException.class);
        verify(permissions).hasPermission(42L,SUPPLIER);verifyNoInteractions(shares);
    }
    @ParameterizedTest @ValueSource(strings={"handler","policy","action","model","permissions","type","unsafe","actor","tenant","user"})
    void mismatchedOwnerMetadataOrIdentityCannotTurnDomainPermissionsIntoAnOrBypass(String fault) {
        bindOwner();
        switch(fault) {
            case "handler" -> config.put("handler","unowned.alias");
            case "policy" -> params.put("targetRecordAuthorization","handler");
            case "action" -> params.put("targetRecordAction","read");
            case "model" -> context.getCommand().setModelCode("other_order");
            case "permissions" -> config.put("permissions",List.of(BUYER,"unowned.permission"));
            case "type" -> config.put("type","query");
            case "unsafe" -> params.put("relatedUpdateAuthorization",true);
            case "actor" -> when(owner.businessActorAuthorization()).thenReturn(null);
            case "tenant" -> MetaContext.setContext(8L,42L,"actor","example");
            case "user" -> MetaContext.setContext(7L,43L,"actor","example");
            default -> throw new AssertionError(fault);
        }
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(permissions,shares);
    }
}
