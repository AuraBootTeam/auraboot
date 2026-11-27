package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Protocol rejection and concurrency admission are isolated from the separately exercised real data boundary. */
class OpenApiEndpointServiceTest {
    private final OpenApiEndpointPublicationService publications = mock(OpenApiEndpointPublicationService.class);
    private final OpenApiEndpointValidator validator = mock(OpenApiEndpointValidator.class);
    private final OpenApiResourceService resources = mock(OpenApiResourceService.class);
    private final OpenApiActionService actions = mock(OpenApiActionService.class);
    private final OpenApiQueryService queries = mock(OpenApiQueryService.class);
    private final OpenApiPublicationMapper mapper = mock(OpenApiPublicationMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OpenApiSynchronousIntegrationService integrations = mock(OpenApiSynchronousIntegrationService.class);
    private final OpenApiEndpointService service = new OpenApiEndpointService(publications,validator,resources,actions,queries,mapper,
            new OpenApiProtocolHash(json,new OpenPlatformSecretCodec()),json,integrations);
    private final MachinePrincipal principal = new MachinePrincipal(1L,"token","machine",Set.of("office.endpoint.invoke"),"app","installation","development","token");
    private OpenApiEndpointContract allow(OpenApiEndpointKind kind) {
        var contract = new OpenApiEndpointContract("office.endpoint","dynamic.get",kind.code(),"office.devices",1,"a".repeat(64),Map.of("invoke","office.endpoint.invoke"),5000,2);
        when(publications.authority(principal,contract.code())).thenReturn(Optional.of(new OpenApiEndpointPublicationService.Authority(contract,new OpenApiEndpointPolicy(true),1L)));
        when(mapper.tryQuerySlot(anyLong(),anyString(),anyInt())).thenReturn(true);return contract;
    }
    @Test void integrationUsesPublishedAuthorityAndCannotAcceptAnInternalTargetOrCallerSelectedAdapter() {
        var contract=allow(OpenApiEndpointKind.INTEGRATION);
        var input=Map.<String,Object>of("orderIds",List.of("10"));
        var expected=new OpenApiResourceService.Result(200,Map.of("requestId","request-123","status","completed"),Map.of(),false);
        when(integrations.execute(principal,contract.bindingCode(),contract.code(),"request-123",input)).thenReturn(expected);
        assertThat(service.invoke(principal,contract.code(),"POST",null,Map.of(),Map.of("input",input),"request-123",null)).isEqualTo(expected);
        clearInvocations(integrations);
        assertThatThrownBy(()->service.invoke(principal,contract.code(),"POST",null,Map.of(),Map.of("input",input,"targetPid","foreign"),"request-123",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.invoke(principal,contract.code(),"POST",null,Map.of(),Map.of("input",input),"request-123","7")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(integrations,resources,actions,queries);
    }
    @Test void unavailableOrDisallowedEndpointNeverReachesBusinessServices() {
        assertThatThrownBy(() -> service.invoke(null,"office.endpoint","GET",null,Map.of(),null,null,null)).isInstanceOf(AccessDeniedException.class);
        when(publications.authority(principal,"office.endpoint")).thenReturn(Optional.empty());
        when(publications.active(1L,"office.endpoint")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.invoke(principal,"office.endpoint","GET",null,Map.of(),null,null,null)).isInstanceOf(ResponseStatusException.class);
        var contract = allow(OpenApiEndpointKind.RESOURCE_GET);
        when(publications.authority(principal,contract.code())).thenReturn(Optional.of(new OpenApiEndpointPublicationService.Authority(contract,new OpenApiEndpointPolicy(false),1L)));
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of(),null,null,null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(resources,actions,queries);
    }
    @Test void changedSourcesWrongMethodsAndAmbiguousInputsFailBeforeExecution() {
        var contract = allow(OpenApiEndpointKind.RESOURCE_GET);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"POST","pid",Map.of(),null,null,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET",null,Map.of(),null,null,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","../private",Map.of(),null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of("tenantId",List.of("other")),null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of(),Map.of(),null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of(),null,"write-key",null)).isInstanceOf(IllegalArgumentException.class);
        doThrow(new IllegalArgumentException("source changed")).when(validator).validate(1L,contract);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of(),null,null,null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("source_changed");
        verifyNoInteractions(resources,actions,queries);
    }
    @Test void exhaustedConcurrentSlotsReturn429WithoutBorrowingAnotherEndpointLane() {
        var contract = allow(OpenApiEndpointKind.RESOURCE_GET);when(mapper.tryQuerySlot(anyLong(),anyString(),anyInt())).thenReturn(false);
        assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET","pid",Map.of(),null,null,null))
                .isInstanceOfSatisfying(ResponseStatusException.class,error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        verify(mapper).tryQuerySlot(1L,"endpoint:office.endpoint",0);verify(mapper).tryQuerySlot(1L,"endpoint:office.endpoint",1);
        verifyNoInteractions(resources,actions,queries);
    }
    @Test void listDoesNotAcceptSpoofedAuthorityRepeatedParamsOrUnboundedNumericParsing() {
        var contract = allow(OpenApiEndpointKind.RESOURCE_LIST);
        for (Map<String,List<String>> input : List.of(Map.of("limit",List.of("1","2")),Map.of("limit",List.of("999999999999")),Map.of("tenantId",List.of("other"))))
            assertThatThrownBy(() -> service.invoke(principal,contract.code(),"GET",null,input,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(resources,actions,queries);
    }
    @Test void typedQueryAndActionBodiesCannotSmuggleInternalSourceOrImpreciseIntegers() {
        var contract = allow(OpenApiEndpointKind.QUERY);
        for (Map<String,Object> body : List.<Map<String,Object>>of(Map.of("source","private"),Map.of("parameters","SQL"),Map.of("limit",2.5),Map.of("cursor",123)))
            assertThatThrownBy(() -> service.invoke(principal,contract.code(),"POST",null,Map.of(),body,null,null)).isInstanceOf(IllegalArgumentException.class);
        allow(OpenApiEndpointKind.ACTION);
        for (Map<String,Object> body : List.<Map<String,Object>>of(Map.of("input",Map.of(),"source","private"),Map.of("input",List.of()),Map.of("input",Map.of(),"targetPid",123)))
            assertThatThrownBy(() -> service.invoke(principal,contract.code(),"POST",null,Map.of(),body,null,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(resources,actions,queries);
    }
}
