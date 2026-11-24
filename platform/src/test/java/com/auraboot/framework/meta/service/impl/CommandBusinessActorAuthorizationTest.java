package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.permission.engine.model.*;
import com.auraboot.framework.plugin.extension.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandBusinessActorAuthorizationTest {
    final DynamicDataService data=mock(DynamicDataService.class);
    final BusinessResourceAuthorization owner=mock(BusinessResourceAuthorization.class);
    final Map<String,Object> root=Map.of("pid","order","tenant_id",7L,"supplier","supplier-a");
    final Map<String,Object> member=Map.of("pid","member","tenant_id",7L,"row_version",0L,"user_pid","actor","state","active","secret","never exposed");
    CommandPipelineContext context;
    @BeforeEach void setup() {
        MetaContext.setContext(7L,20L,"actor","Actor");MetaContext.setMemberId(30L);
        var command=new CommandDefinition();command.setCode("orders:export");command.setModelCode("orders");
        var request=new CommandExecuteRequest();request.setTargetRecordId("order");request.setPayload(Map.of("authorized",true));
        context=CommandPipelineContext.builder().commandCode("orders:export").command(command).request(request).tenantId(7L).userId(20L).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("orders.export"));
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization("orders:export","orders","order","update",7L,20L,30L));
        context.setBusinessGrantDecision(new BusinessGrantDecision(PermissionResult.allow(List.of()),7L,20L,30L,"orders","order",
            List.of(new BusinessGrantDecision.Grant(1L,2L,"orders.export",BusinessGrantDecision.Fields.legacy()))));
    }
    @AfterEach void clear(){MetaContext.clear();}
    void policy(Map<String,Set<String>> fields,Function<BusinessActorAuthorization.Context,Boolean> check) {
        when(owner.businessActorAuthorization()).thenReturn(new BusinessActorAuthorization() {
            public Map<String,Set<String>> sources(){return fields;}
            public boolean authorize(Context value){return check.apply(value);}
        });
    }
    void run(){CommandBusinessActorAuthorization.require(context,owner,data,root);}

    @Test void currentActorGetsOnlyDeclaredIdentityFieldsAndHostDerivedGrants() {
        when(data.getByIdForProcessing("members","member")).thenReturn(member);
        policy(Map.of("members",Set.of("user_pid","state")),value->{
            assertThat(value.grantedPermissions()).containsExactly("orders.export");
            assertThat(value.target().currentUserPid()).isEqualTo("actor");
            assertThat(value.target().record()).isEqualTo(root);
            var row=value.target().records().getById("members","member");
            assertThat(row).containsOnlyKeys("pid","tenant_id","row_version","user_pid","state");
            assertThatThrownBy(()->row.put("state","active")).isInstanceOf(UnsupportedOperationException.class);
            return "active".equals(row.get("state"));
        });
        run();verify(data).getByIdForProcessing("members","member");
    }
    @Test void aRevokedIdentityRejectsAnOtherwiseCompleteGrantAndPayloadCannotOverrideIt() {
        policy(Map.of(),value->false);
        assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);
    }
    @Test void olderOwnersWithoutAnIntrinsicActorGateKeepTheirExistingAuthorizationPath() {
        run();verifyNoInteractions(data);
    }
    @Test void undeclaredModelsFieldsKeywordAndNonFirstPagesAreRefusedBeforeAnyLookup() {
        for(var action:List.<java.util.function.Consumer<RecordReadAccessor>>of(
            reads->reads.getById("secrets","member"),
            reads->reads.queryPage("members",new DataPageQuery(Map.of("secret","x"),Map.of(),List.of(),1,2)),
            reads->reads.queryPage("members",new DataPageQuery(Map.of(),Map.of(),List.of(),1,2,"",
                    Map.of("secret",new DataPageQuery.TimeRange(java.time.Instant.EPOCH,java.time.Instant.EPOCH.plusSeconds(1))))),
            reads->reads.queryPage("members",new DataPageQuery(Map.of(),Map.of(),List.of(new DataPageQuery.Sort("secret",false)),1,2)),
            reads->reads.queryPage("members",new DataPageQuery(Map.of(),Map.of(),List.of(),2,2)),
            reads->reads.queryPage("members",new DataPageQuery(Map.of(),Map.of(),List.of(),1,101)),
            reads->reads.queryPage("members",new DataPageQuery(Map.of(),Map.of(),List.of(),1,2,"enumerate")))) {
            policy(Map.of("members",Set.of("user_pid","state")),value->{action.accept(value.target().records());return true;});
            assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(data);
    }
    @Test void identityQueryProjectsEveryRowAndAccountsForTheRequestedBudget() {
        when(data.list(eq("members"),any())).thenReturn(PaginationResult.of(List.of(member),1L,1,2));
        policy(Map.of("members",Set.of("state")),value->{
            var query=new DataPageQuery(Map.of("state","active"),Map.of(),List.of(),1,2);
            for(int i=0;i<50;i++)assertThat(value.target().records().queryPage("members",query).records().getFirst()).doesNotContainKeys("secret","user_pid");
            assertThatThrownBy(()->value.target().records().queryPage("members",query)).isInstanceOf(AccessDeniedException.class);return true;
        });assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);verify(data,times(50)).list(eq("members"),any());
    }
    @Test void foreignDeletedWrongPidAndUnversionedIdentityRowsDenyInsteadOfDisappearing() {
        for(String field:List.of("tenant_id","deleted_flag","pid","row_version")) {
            var row=new HashMap<>(member);
            switch(field){case "tenant_id"->row.put(field,8L);case "deleted_flag"->row.put(field,true);case "pid"->row.put(field,"other");default->row.remove(field);}
            when(data.getByIdForProcessing("members","member")).thenReturn(row);
            policy(Map.of("members",Set.of("state")),value->{value.target().records().getById("members","member");return true;});
            assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void retainedOrCrossThreadReadHandlesCannotOutliveTheGate() {
        var saved=new AtomicReference<RecordReadAccessor>();
        policy(Map.of("members",Set.of("state")),value->{
            saved.set(value.target().records());
            var result=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{saved.get().getById("members","member");return false;}catch(AccessDeniedException expected){return true;}
            });assertThat(result.join()).isTrue();return true;
        });assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->saved.get().getById("members","member")).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);
    }
    @Test void actorChangesAndForeignRootFactsDenyBeforeReadingIdentityDependencies() {
        policy(Map.of(),value->{MetaContext.setMemberId(31L);value.target().records().getById("members","member");return true;});
        assertThatThrownBy(this::run).isInstanceOf(AccessDeniedException.class);MetaContext.setMemberId(30L);
        assertThatThrownBy(()->CommandBusinessActorAuthorization.require(context,owner,data,Map.of("pid","order","tenant_id",8L)))
            .isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);
    }
}
