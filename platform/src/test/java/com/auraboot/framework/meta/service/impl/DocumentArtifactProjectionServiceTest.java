package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.security.BusinessArtifactAuthorization;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.security.DocumentArtifactContract;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentArtifactProjectionServiceTest {
    static final String PID="01ARZ3NDEKTSV4RRFFQ69G5FAV",COMMAND="quote:generate";
    final CommandDefinitionMapper commands=mock(CommandDefinitionMapper.class);
    final DynamicDataService data=mock(DynamicDataService.class);
    final BusinessCommandRecordAuthorization authorization=mock(BusinessCommandRecordAuthorization.class);
    final CommandPublicOutputProjectionService projections=mock(CommandPublicOutputProjectionService.class);
    final ObjectMapper json=new ObjectMapper();
    final DocumentArtifactProjectionService service=new DocumentArtifactProjectionService(commands,data,authorization,projections,json);
    CommandDefinition command;
    Map<String,Object> config;
    FileEntity file;
    BusinessArtifactAuthorization stamp;
    CommandPublicOutputProjectionPlan visible;
    @BeforeEach void prepare() throws Exception {
        MetaContext.setContext(7L,20L,"actor","Actor");MetaContext.setMemberId(30L);
        config=Map.of("handlerParams",Map.of("documentDefinition",Map.of("code","quote.standard","version","1.0.0",
                "dataset","quote:snapshot_v1","template","standard_v1","outputs",List.of("xlsx","pdf"),
                "versionPolicy","immutable-artifact","artifactRelation","formal_documents","publicFields",Map.of("quote",List.of("code")))));
        var contract=DocumentArtifactContract.from(config,"quote");
        command=new CommandDefinition();command.setCode(COMMAND);command.setModelCode("quote");command.setTenantId(7L);
        command.setIsCurrent(true);command.setDeletedFlag(false);command.setStatus("published");command.setExecutionConfig(json.writeValueAsString(config));
        when(commands.findCurrentByCode(COMMAND)).thenReturn(command);
        var root=Map.<String,Object>of("pid",PID,"tenant_id",7L,"code","Q-001");
        when(data.getByIdForAuthorization("quote",PID)).thenReturn(root);
        var grant=new BusinessGrantDecision(PermissionResult.allow(List.of()),7L,20L,30L,"quote",PID,
                List.of(new BusinessGrantDecision.Grant(1L,2L,"qo.cap.read",BusinessGrantDecision.Fields.legacy())));
        when(authorization.resolveOrdinaryRead(30L,"quote",root)).thenReturn(grant);
        visible=plan(Set.of("code"));
        when(projections.prepareDocument(any(),any())).thenReturn(visible);
        stamp=BusinessArtifactAuthorization.captureDocument(visible,"formal_documents",
                new BusinessArtifactAuthorization.Document(contract.code(),contract.version(),contract.fingerprint(),"xlsx"));
        file=new FileEntity();file.setTenantId(7L);file.setDeletedFlag(false);file.setStatus("success");file.setRetentionLocked(true);
    }
    private CommandPublicOutputProjectionPlan plan(Set<String> readable) {
        return new CommandPublicOutputProjectionPlan(new CommandPipelineContext.BusinessTargetAuthorization(COMMAND,"quote",PID,"read",7L,20L,30L),null,
                Map.of("quote",new CommandPublicOutputProjectionPlan.ModelFields(Set.of("code"),readable,List.of(),List.of())));
    }
    @AfterEach void clear(){MetaContext.clear();}
    @Test void unchangedCurrentReadAndFieldViewAuthorizesTheOriginalArtifact() {
        service.authorize(file,stamp);
        verify(authorization).resolveOrdinaryRead(eq(30L),eq("quote"),anyMap());
        verify(projections).prepareDocument(argThat(context->context.getBusinessGrantDecision()!=null
                && "read".equals(context.getBusinessTargetAuthorization().action())),eq(Map.of("quote",Set.of("code"))));
    }
    @Test void hiddenSourceFieldDeniesAndRestoringTheViewReadsTheSameStamp() {
        when(projections.prepareDocument(any(),any())).thenReturn(plan(Set.of()));
        assertThatThrownBy(()->service.authorize(file,stamp)).isInstanceOf(AccessDeniedException.class);
        when(projections.prepareDocument(any(),any())).thenReturn(visible);
        service.authorize(file,stamp);
        verify(data,never()).update(anyString(),anyString(),anyMap());
    }
    @Test void changedTemplateCannotReuseTheOldSourceStamp() throws Exception {
        command.setExecutionConfig(command.getExecutionConfig().replace("standard_v1","standard_v2"));
        assertThatThrownBy(()->service.authorize(file,stamp)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data,authorization,projections);
    }
    @Test void staleCommandOrUnretainedFileCannotUseTheDocumentPath() {
        command.setIsCurrent(false);
        assertThatThrownBy(()->service.authorize(file,stamp)).isInstanceOf(AccessDeniedException.class);
        command.setIsCurrent(true);file.setRetentionLocked(false);
        assertThatThrownBy(()->service.authorize(file,stamp)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data,authorization,projections);
    }
    @Test void anotherTenantCannotReachTheRootOrItsPolicies() {
        MetaContext.setContext(8L,20L,"actor","Actor");MetaContext.setMemberId(30L);
        assertThatThrownBy(()->service.authorize(file,stamp)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(commands,data,authorization,projections);
    }
    private CommandPipelineContext resultContext() {
        var request=new com.auraboot.framework.meta.dto.CommandExecuteRequest();request.setTargetRecordId(PID);
        var context=CommandPipelineContext.builder().commandCode(COMMAND).command(command).request(request)
                .tenantId(7L).userId(20L).execConfig(config).targetRecordReadable(true).build();
        context.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("qo.cap.write"));
        var grant=new BusinessGrantDecision(PermissionResult.allow(List.of()),7L,20L,30L,"quote",PID,
                List.of(new BusinessGrantDecision.Grant(1L,2L,"qo.cap.write",BusinessGrantDecision.Fields.legacy())));
        context.setBusinessGrantDecision(grant);
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(COMMAND,"quote",PID,"update",7L,20L,30L));
        var fields=new HashMap<String,com.auraboot.framework.plugin.extension.BusinessResultField>();
        for(String property:List.of("fileId","filename","bytes","url"))fields.put(property,
                com.auraboot.framework.plugin.extension.BusinessResultField.documentArtifact("xlsx",property));
        context.setBusinessResultProjection(new BusinessResultProjectionPlan(context.getBusinessTargetAuthorization(),grant,
                fields,Map.of(),Set.of(),List.of(),List.of()));
        var files=mock(com.auraboot.framework.file.dao.mapper.FileMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"files",files);
        file.setPid(PID);file.setOriginalName("Q-001.xlsx");file.setFileSize(12L);
        file.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");file.setArtifactAuthorization(stamp.encode());
        when(files.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(file);
        return context;
    }
    private Map<String,Object> result() {return Map.of("fileId",PID,"filename","Q-001.xlsx","bytes",12,"url","/api/file/download/"+PID);}
    @Test void freshAndReplayedResultsRequireTheSameHostFileFacts() {
        var context=resultContext();service.verifyResults(context,result());service.verifyResults(context,new HashMap<>(result()));
        assertThat(context.getBusinessResultProjection().validateEnvelope(result())).containsAllEntriesOf(result());
    }
    @Test void fileNameSizeAndLinkCannotCarryArbitraryHiddenSourceValues() {
        var context=resultContext();
        for(var change:List.of(Map.entry("filename","private-cost.xlsx"),Map.entry("url","https://other.invalid/private"),Map.entry("bytes",99))) {
            var forged=new HashMap<>(result());forged.put(change.getKey(),change.getValue());
            assertThatThrownBy(()->service.verifyResults(context,forged)).isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void sameTenantOtherRootArtifactCannotBeReturnedByThisDocumentCommand() {
        var context=resultContext();
        var other=new BusinessArtifactAuthorization(2,7L,COMMAND,"quote","01ARZ3NDEKTSV4RRFFQ69G5FAW",
                stamp.rootField(),stamp.fingerprint(),stamp.models(),Map.of(),stamp.document());
        file.setArtifactAuthorization(other.encode());
        assertThatThrownBy(()->service.verifyResults(context,result())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void replayRechecksCurrentFieldPolicyAndCannotReuseAnUnstampedFile() {
        var context=resultContext();service.verifyResults(context,result());
        when(projections.prepareDocument(any(),any())).thenReturn(plan(Set.of()));
        assertThatThrownBy(()->service.verifyResults(context,result())).isInstanceOf(AccessDeniedException.class);
        when(projections.prepareDocument(any(),any())).thenReturn(visible);
        service.verifyResults(context,result());file.setArtifactAuthorization(null);
        assertThatThrownBy(()->service.verifyResults(context,result())).isInstanceOf(AccessDeniedException.class);
    }
}
