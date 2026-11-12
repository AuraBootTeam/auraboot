package com.auraboot.framework.file.security;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.entity.payload.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionService;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.FileAccessorImpl;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/** Real fresh-Flyway schema, full grants and local storage. Not a product generator or browser journey. */
@SpringBootTest(classes = TestApplication.class, properties = "spring.data.redis.host=")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessArtifactAccessPostgresIT extends BaseIntegrationTest {
    private static final byte[] BYTES = "CONFIDENTIAL-BOARD\n".getBytes(StandardCharsets.UTF_8);
    private static final Path STORE;
    static { try { STORE = Files.createTempDirectory("aura-artifact-read-"); } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); } }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("aura.storage.type", () -> "local"); registry.add("aura.storage.local.base-path", STORE::toString);
    }
    @Autowired MetaModelMapper models;
    @Autowired MetaFieldMapper fields;
    @Autowired MetaModelFieldBindingMapper bindings;
    @Autowired CommandDefinitionMapper commands;
    @Autowired MetaModelService metadata;
    @Autowired SchemaManagementService schema;
    @Autowired DynamicDataService data;
    @Autowired FileMapper files;
    @Autowired FileService fileService;
    @Autowired StorageProvider storage;
    @Autowired DataScopeService scopes;
    @Autowired BusinessCommandRecordAuthorization grants;
    @Autowired CommandPublicOutputProjectionService projection;
    @Autowired com.auraboot.framework.meta.service.impl.DocumentArtifactProjectionService documentProjection;
    @Autowired com.auraboot.framework.meta.service.CommandExecutor commandExecutor;
    @Autowired FieldMaskService masks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.auraboot.framework.user.mapper.UserMapper users;
    @Autowired com.auraboot.framework.tenant.service.TenantMemberService members;
    @Autowired com.auraboot.framework.rbac.service.UserRoleService roles;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.auraboot.framework.plugin.pf4j.BusinessCommandDataAccessorFactory resourceFactory;
    @MockitoSpyBean ExtensionRegistry extensions;
    String model, fileField, secret, projectField, intentField, command, permission, rootPid, filePid, table;
    Long modelId, commandId;
    final List<Long> fieldIds = new ArrayList<>(), maskIds = new ArrayList<>();
    Owner owner;

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='ab_file' AND column_name='artifact_authorization'",Integer.class)).isEqualTo(1);
        String suffix = UniqueIdGenerator.generate().toLowerCase(Locale.ROOT);
        model = "artifact_" + suffix; fileField = "artifact_file_" + suffix; secret = "artifact_secret_" + suffix;
        projectField = "artifact_project_" + suffix; intentField = "artifact_run_" + suffix;
        command = model + ":export"; permission = "fixture." + model + ".export";
        var definition = new Model(); definition.setPid(UniqueIdGenerator.generate()); definition.setTenantId(testTenant.getId());
        definition.setCode(model); definition.setVersion(1); definition.setIsCurrent(true); definition.setStatus("published"); definition.setDeletedFlag(false);
        definition.setCreatedAt(Instant.now()); definition.setUpdatedAt(Instant.now());
        var extension = new ExtensionBean(); extension.setExtension(new HashMap<>(Map.of("modelType", "entity", "softDelete", true))); definition.setExtension(extension);
        models.insert(definition); modelId = definition.getId(); bind(fileField, "file"); bind(secret, "string"); bind(projectField,"string"); bind(intentField,"string"); metadata.clearAllCache();
        var created = schema.createTableByModel(model); assertThat(created.isSuccess()).as(created.getErrorMessage()).isTrue();
        table = metadata.getModelDefinition(model).orElseThrow().getTableName();
        rootPid = MetaContext.runWithCommandPermitPlan("ALL",null,model,null,
                () -> Objects.toString(data.create(model,new HashMap<>(Map.of(secret,"BOARD",projectField,"PROJECT-A",intentField,"RUN-A"))).get("pid")));
        grantCommittedPermissionToTestRole(permission,"operation",model,"manage","Artifact generation fixture");
        scopes.setScope(testTenant.getId(),testRole.getId(),model,"manage","all","MAX");
        owner = new Owner(); doReturn(Optional.of(owner)).when(extensions).getCommandHandler(command);
        var entry = new CommandDefinition(); entry.setPid(UniqueIdGenerator.generate()); entry.setTenantId(testTenant.getId()); entry.setCode(command);
        entry.setModelCode(model); entry.setDisplayName("Artifact fixture"); entry.setVersion(1); entry.setIsCurrent(true); entry.setStatus("published"); entry.setDeletedFlag(false);
        entry.setInputSchema("{}"); entry.setTargetModels("[]"); entry.setCmdRiskLevel("L1");
        entry.setExecutionConfig(json.writeValueAsString(Map.of("type","custom","permissions",List.of(permission),"handler",command,"cacheResult",true,
                "handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false))));
        commands.insert(entry); commandId = entry.getId();
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.execute(status -> {
            var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid);
            var context = CommandPipelineContext.builder().commandCode(command).command(entry).request(request).tenantId(testTenant.getId()).userId(testUser.getId())
                    .authorizationVerdict(CommandAuthorizationVerdict.authorized(permission)).targetRecordReadable(true).build();
            context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command,model,rootPid,"update",testTenant.getId(),testUser.getId(),testTenantMember.getId()));
            var root = MetaContext.runWithCommandPermitPlan("TARGET",null,model,rootPid,() -> data.getByIdForAuthorization(model,rootPid));
            context.setBusinessGrantDecision(grants.resolve(testTenantMember.getId(),List.of(permission),model,root));
            assertThat(context.getBusinessGrantDecision().result().granted()).isTrue();
            var saved = fileService.persistBusinessArtifact(new MockMultipartFile("file","board.txt","text/plain",BYTES),
                    testUser.getId(), BusinessArtifactAuthorization.capture(projection.prepare(context,owner),fileField,projection.prepareArtifactReadBinding(context,owner,root)));
            filePid = saved.getPid();
            assertThat(saved.getRetentionLocked()).isTrue();
            return null;
        });
        assertThat(fileService.findByPid(filePid).getTenantId()).isEqualTo(testTenant.getId());
    }

    @Test void a_versioned_document_saved_through_the_host_accessor_rechecks_current_source_masks() throws Exception {
        String document=saveVersionedDocument(false,new java.util.concurrent.atomic.AtomicReference<>());
        var saved=fileService.findByPid(document);
        assertThat(saved.getRetentionLocked()).isTrue();
        assertThat(BusinessArtifactAuthorization.parse(saved.getArtifactAuthorization()).document().format()).isEqualTo("xlsx");
        try(var input=accessor().open(document)){assertThat(input.readAllBytes()).isEqualTo(BYTES);}
        var mask=new FieldMaskConfig();mask.setModelCode(model);mask.setFieldCode(secret);mask.setEnabled(true);
        mask.setApplyToDetail(false);mask.setApplyToExport(true);mask.setMaskType("FULL");mask.setReplacementChar("*");
        masks.saveConfig(mask);maskIds.add(mask.getId());
        assertThatThrownBy(()->accessor().open(document)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->fileService.getFileDownloadUrl(document)).isInstanceOf(AccessDeniedException.class);
        masks.deleteConfig(mask.getId());maskIds.remove(mask.getId());
        try(var input=accessor().open(document)){assertThat(input.readAllBytes()).isEqualTo(BYTES);}
        assertThat(fileService.findByPid(document).getArtifactAuthorization()).isEqualTo(saved.getArtifactAuthorization());
    }

    @Test void a_versioned_document_rolls_back_its_bytes_relation_and_business_write_together() throws Exception {
        long fileCount=jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        long relationCount=jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=?",Long.class,testTenant.getId());
        var key=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThatThrownBy(()->saveVersionedDocument(true,key)).isInstanceOf(IllegalStateException.class)
                .hasMessage("abort versioned document transaction");
        assertThat(key.get()).isNotBlank();
        assertThat(storage.exists(key.get())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(fileCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(relationCount);
        assertThat(jdbc.queryForObject("SELECT "+secret+" FROM "+table+" WHERE pid=?",String.class,rootPid)).isEqualTo("BOARD");
    }

    private String saveVersionedDocument(boolean abort,java.util.concurrent.atomic.AtomicReference<String> storageKey) throws Exception {
        grantCommittedPermissionToTestRole("model."+model+".read","model",model,"read","Document reader fixture");
        grantScope("read","all");
        var entry=commands.selectById(commandId);
        var config=new HashMap<String,Object>(json.readValue(entry.getExecutionConfig(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        var parameters=new HashMap<String,Object>();
        parameters.put("documentDefinition",Map.of("code","fixture.standard","version","1.0.0","dataset",model+":snapshot_v1",
                "template","fixture_v1","outputs",List.of("xlsx","pdf"),"versionPolicy","immutable-artifact",
                "artifactRelation",fileField,"publicFields",Map.of(model,List.of(secret))));
        config.put("handlerParams",parameters);entry.setExecutionConfig(json.writeValueAsString(config));commands.updateById(entry);
        var request=new CommandExecuteRequest();request.setTargetRecordId(rootPid);
        var context=CommandPipelineContext.builder().commandCode(command).command(entry).request(request)
                .tenantId(testTenant.getId()).userId(testUser.getId()).execConfig(config)
                .authorizationVerdict(CommandAuthorizationVerdict.authorized(permission)).targetRecordReadable(true).build();
        var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->MetaContext.runWithCommandPermitPlan("TARGET",null,model,rootPid,
                ()->MetaContext.runWithCommandAuthority(permission,()->MetaContext.runWithCommandAggregate(rootPid,
                ()->MetaContext.runWithAuthorizedCommandCode(command,()->{
                    try {
                        return com.auraboot.framework.meta.security.DocumentArtifactCommandScope.within(context,
                                ()->documentProjection.prepare(context),()->{
                                    String pid=accessor().saveAndLink("document.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            BYTES,model,rootPid,fileField).fileId();
                                    if(abort) {
                                        storageKey.set(fileService.findByPid(pid).getFileName());
                                        data.update(model,rootPid,new HashMap<>(Map.of(secret,"CHANGED")));
                                        throw new IllegalStateException("abort versioned document transaction");
                                    }
                                    return pid;
                                });
                    } catch(RuntimeException failure){throw failure;}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                })))));
    }

    @Test void changed_root_relationship_blocks_old_bytes_even_while_the_duty_still_allows_the_root() {
        jdbc.update("UPDATE "+table+" SET "+projectField+"=? WHERE pid=?","PROJECT-B",rootPid);
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessor().describe(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileService.getFileDownloadUrl(filePid)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("UPDATE "+table+" SET "+projectField+"=? WHERE pid=?","PROJECT-A",rootPid);
        try (var input = accessor().open(filePid)) { assertThat(input.readAllBytes()).isEqualTo(BYTES); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    @Test void a_root_binding_change_during_save_aborts_file_and_root_changes() {
        owner.allowBindingMutation = true;
        long before = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        assertThatThrownBy(() -> withResources(accessor -> {
            accessor.getById(model,rootPid); accessor.projectForPublicOutput(model,List.of(rootPid));
            return accessor.savePublicArtifact(fileField,"changed.txt","text/plain",BYTES,Map.of(projectField,"PROJECT-B"));
        })).isInstanceOf(DataAccessorException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT "+projectField+" FROM "+table+" WHERE pid=?",String.class,rootPid)).isEqualTo("PROJECT-A");
    }

    @Test void an_owner_backed_update_checks_finite_input_fields_before_warm_replay() throws Exception {
        var entry=commands.selectById(commandId);
        var config=json.readValue(entry.getExecutionConfig(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        config.put("type","update");config.put("inputFields",List.of(secret));
        entry.setExecutionConfig(json.writeValueAsString(config));commands.updateById(entry);
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Owner-backed update fixture");
        var request=new CommandExecuteRequest();request.setTargetRecordId(rootPid);
        request.setClientRequestId(UniqueIdGenerator.generate());request.setPayload(Map.of(secret,"BOARD"));
        var first=commandExecutor.execute(command,request);
        assertThat(first.getData()).containsEntry("success",true);
        assertThat(owner.executions).hasValue(1);
        long count=jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isTrue();
        String outcome=jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class,testTenant.getId(),command,command+":"+request.getClientRequestId());
        var stale=json.readValue(outcome,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        stale.put(secret,"OLD_RAW_ROW_MUST_NOT_LEAVE_THE_BOUNDARY");
        jdbc.update("UPDATE ab_idempotency_record SET outcome=?::jsonb WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                json.writeValueAsString(stale),testTenant.getId(),command,command+":"+request.getClientRequestId());
        assertThatThrownBy(() -> commandExecutor.execute(command,request)).isInstanceOf(AccessDeniedException.class);
        assertThat(owner.executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
        jdbc.update("UPDATE ab_idempotency_record SET outcome=?::jsonb WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                outcome,testTenant.getId(),command,command+":"+request.getClientRequestId());
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isTrue();
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(secret,fileField),"writeFields",List.of())),
                testTenant.getId(),testRole.getId(),testTenant.getId(),permission);
        assertThatThrownBy(() -> commandExecutor.execute(command,request)).isInstanceOfSatisfying(BusinessException.class,
                denied -> assertThat(denied.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN));
        assertThat(owner.executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
        var parameters=new HashMap<String,Object>((Map<String,Object>)config.get("handlerParams"));
        parameters.put("dslPersistence",true);config.put("handlerParams",parameters);
        entry.setExecutionConfig(json.writeValueAsString(config));commands.updateById(entry);
        assertThatThrownBy(() -> commandExecutor.execute(command,request)).isInstanceOfSatisfying(BusinessException.class,
                denied -> assertThat(denied.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN));
        assertThat(owner.executions).hasValue(1);
    }

    @Test void actual_command_cache_conflicts_on_root_relationship_change_and_restores_without_reexecution() {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Command fixture invocation");
        var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid); request.setOperationType("update");
        request.setClientRequestId(UniqueIdGenerator.generate()); request.setPayload(Map.of());
        var first = commandExecutor.execute(command,request);
        assertThat(first.isIdempotentReplay()).isFalse(); assertThat(first.getData()).containsEntry("success",true);
        assertThat(owner.executions).hasValue(1);
        String fileId = first.getData().get("fileId").toString();
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        String outcome = jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class,testTenant.getId(),command,command+":"+request.getClientRequestId());
        jdbc.update("UPDATE "+table+" SET "+projectField+"=?, row_version=row_version+1 WHERE pid=?","PROJECT-B",rootPid);
        assertThatThrownBy(() -> commandExecutor.execute(command,request))
                .isInstanceOf(com.auraboot.framework.exception.RequestIntentConflictException.class);
        assertThatThrownBy(() -> accessor().open(fileId)).isInstanceOf(AccessDeniedException.class);
        assertThat(owner.executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class,testTenant.getId(),command,command+":"+request.getClientRequestId())).isEqualTo(outcome);
        jdbc.update("UPDATE "+table+" SET "+projectField+"=?, row_version=row_version+1 WHERE pid=?","PROJECT-A",rootPid);
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isTrue();
        assertThat(owner.executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
    }

    @Test void a_soft_deleted_root_blocks_known_artifact_routes_but_retains_audit_bytes() throws Exception {
        var original = fileService.findByPid(filePid);
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        assertThat(accessor().describe(filePid)).isNotNull();
        assertThat(fileService.getFileDownloadUrl(filePid)).isNotBlank();
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());

        assertThat(jdbc.update("UPDATE "+table+" SET deleted_flag=true, row_version=row_version+1 WHERE tenant_id=? AND pid=?",
                testTenant.getId(),rootPid)).isEqualTo(1);
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessor().describe(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileService.getFileDownloadUrl(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT deleted_flag FROM "+table+" WHERE tenant_id=? AND pid=?",
                Boolean.class,testTenant.getId(),rootPid)).isTrue();
        var retained = fileService.findByPid(filePid);
        assertThat(retained.getArtifactAuthorization()).isEqualTo(original.getArtifactAuthorization());
        assertThat(retained.getRetentionLocked()).isTrue();
        assertThat(storage.exists(original.getFileName())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);

        // Restore only this test's fixture: the unchanged grants still allow the same retained file.
        assertThat(jdbc.update("UPDATE "+table+" SET deleted_flag=false, row_version=row_version+1 WHERE tenant_id=? AND pid=?",
                testTenant.getId(),rootPid)).isEqualTo(1);
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
    }

    @Test void a_soft_deleted_root_cannot_replay_a_warm_command_or_generate_another_artifact() throws Exception {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Command fixture invocation");
        var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid); request.setOperationType("update");
        request.setClientRequestId(UniqueIdGenerator.generate()); request.setPayload(Map.of());
        var first = commandExecutor.execute(command,request);
        assertThat(first.isIdempotentReplay()).isFalse();
        assertThat(first.getData()).containsEntry("success",true);
        String saved = first.getData().get("fileId").toString();
        try (var bytes = accessor().open(saved)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        String outcome = jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class,testTenant.getId(),command,command+":"+request.getClientRequestId());
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        assertThat(jdbc.update("UPDATE "+table+" SET deleted_flag=true, row_version=row_version+1 WHERE tenant_id=? AND pid=?",
                testTenant.getId(),rootPid)).isEqualTo(1);

        assertThatThrownBy(() -> commandExecutor.execute(command,request)).isInstanceOfSatisfying(BusinessException.class,
                denied -> assertThat(denied.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN));
        var fresh = new CommandExecuteRequest(); fresh.setTargetRecordId(rootPid); fresh.setOperationType("update");
        fresh.setClientRequestId(UniqueIdGenerator.generate()); fresh.setPayload(Map.of());
        assertThatThrownBy(() -> commandExecutor.execute(command,fresh)).isInstanceOfSatisfying(BusinessException.class,
                denied -> assertThat(denied.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN));
        assertThatThrownBy(() -> accessor().open(saved)).isInstanceOf(AccessDeniedException.class);
        assertThat(owner.executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class,testTenant.getId(),command,command+":"+request.getClientRequestId())).isEqualTo(outcome);
        assertThat(storage.exists(fileService.findByPid(saved).getFileName())).isTrue();
    }

    @Test void an_intent_only_run_change_blocks_regeneration_replay_without_retiring_historical_bytes() throws Exception {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Command fixture invocation");
        var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid); request.setOperationType("update");
        request.setClientRequestId(UniqueIdGenerator.generate()); request.setPayload(Map.of());
        var first = commandExecutor.execute(command,request); String saved = first.getData().get("fileId").toString();
        jdbc.update("UPDATE "+table+" SET "+intentField+"=?, row_version=row_version+1 WHERE pid=?","RUN-B",rootPid);
        assertThatThrownBy(() -> commandExecutor.execute(command,request))
                .isInstanceOf(com.auraboot.framework.exception.RequestIntentConflictException.class);
        try (var input = accessor().open(saved)) { assertThat(input.readAllBytes()).isEqualTo(BYTES); }
        assertThat(owner.executions).hasValue(1);
        jdbc.update("UPDATE "+table+" SET "+intentField+"=?, row_version=row_version+1 WHERE pid=?","RUN-A",rootPid);
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isTrue();
        assertThat(owner.executions).hasValue(1);
    }

    @Test void generated_bytes_and_database_record_roll_back_with_the_business_transaction() {
        var original = fileService.findByPid(filePid);
        var stamp = BusinessArtifactAuthorization.parse(original.getArtifactAuthorization());
        var saved = new java.util.concurrent.atomic.AtomicReference<FileEntity>();
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> tx.execute(status -> {
            saved.set(fileService.persistBusinessArtifact(new MockMultipartFile("file","rollback.txt","text/plain",BYTES),
                    testUser.getId(), stamp));
            assertThat(storage.exists(saved.get().getFileName())).isTrue();
            jdbc.update("UPDATE " + table + " SET " + secret + " = ? WHERE pid = ?", "UNCOMMITTED", rootPid);
            throw new IllegalStateException("abort generated artifact transaction");
        })).isInstanceOf(IllegalStateException.class).hasMessage("abort generated artifact transaction");
        assertThat(fileService.findByPid(saved.get().getPid())).isNull();
        assertThat(storage.exists(saved.get().getFileName())).isFalse();
        assertThat(jdbc.queryForObject("SELECT " + secret + " FROM " + table + " WHERE pid = ?", String.class, rootPid)).isEqualTo("BOARD");
        assertThat(storage.exists(original.getFileName())).isTrue();
    }

    void bind(String code, String type) {
        var field = new Field(); field.setPid(UniqueIdGenerator.generate()); field.setTenantId(testTenant.getId()); field.setCode(code); field.setDataType(type);
        field.setVersion(1); field.setIsCurrent(true); field.setStatus("published"); field.setDeletedFlag(false); field.setFeature(new FieldFeatureBean());
        field.setCreatedAt(Instant.now()); field.setUpdatedAt(Instant.now()); fields.insert(field); fieldIds.add(field.getId());
        var binding = new ModelFieldBinding(); binding.setTenantId(testTenant.getId()); binding.setModelId(modelId); binding.setFieldId(field.getId()); binding.setFieldOrder(fieldIds.size()); bindings.insert(binding);
    }

    <T> T withResources(java.util.function.Function<com.auraboot.framework.plugin.extension.DataAccessor,T> body) {
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            var root = MetaContext.runWithCommandPermitPlan("TARGET",null,model,rootPid,() -> data.getByIdForAuthorization(model,rootPid));
            long version = ((Number)root.get("row_version")).longValue();
            var entry = commands.selectById(commandId);
            var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid);
            var context = CommandPipelineContext.builder().commandCode(command).command(entry).request(request)
                    .tenantId(testTenant.getId()).userId(testUser.getId()).targetRecordReadable(true).targetRecordVersion(version)
                    .authorizationVerdict(CommandAuthorizationVerdict.authorized(permission))
                    .permitPlan(new com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan(
                            com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.Decision.PERMIT,null,null,rootPid,
                            com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.ScopeGrade.TARGET,version))
                    .execConfig(Map.of("type","custom","permissions",List.of(permission),"handler",command,"cacheResult",true,
                            "handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false))).build();
            context.setBusinessGrantDecision(grants.resolve(testTenantMember.getId(),List.of(permission),model,root));
            context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command,model,rootPid,"update",
                    testTenant.getId(),testUser.getId(),testTenantMember.getId()));
            context.setPublicOutputProjection(projection.prepare(context,owner));
            context.setArtifactRootBinding(projection.prepareArtifactBinding(context,owner,root));
            return MetaContext.runWithCommandPermitPlan("TARGET",version,model,rootPid,() ->
                    MetaContext.runWithAuthorizedCommandCode(command,() -> {
                        try (var scope = resourceFactory.open(context,owner)) { return body.apply(scope.accessor()); }
                    }));
        });
    }

    @Test void scoped_save_commits_root_reference_relation_and_protected_bytes_without_model_grants() throws Exception {
        var saved = withResources(accessor -> {
            accessor.getById(model,rootPid);
            assertThat(accessor.projectForPublicOutput(model,List.of(rootPid)).get(rootPid)).containsEntry(secret,"BOARD");
            return accessor.savePublicArtifact(fileField,"scoped.txt","text/plain",BYTES,Map.of(secret,"PUBLISHED"));
        });
        assertThat(saved.url()).isEqualTo("/api/file/download/"+saved.fileId());
        assertThat(jdbc.queryForObject("SELECT "+fileField+" FROM "+table+" WHERE pid=?",String.class,rootPid)).isEqualTo(saved.fileId());
        var file = fileService.findByPid(saved.fileId());
        assertThat(fileService.getFileRelations(saved.fileId())).singleElement().satisfies(relation -> {
            assertThat(relation.getEntityId()).isEqualTo(rootPid); assertThat(relation.getFieldName()).isEqualTo(fileField);
        });
        assertThat(file.getRetentionLocked()).isTrue();
        try (var input = accessor().open(saved.fileId())) { assertThat(input.readAllBytes()).isEqualTo(BYTES); }
        assertThat(grants.resolveModelRead(testTenantMember.getId(),model,Map.of("pid",rootPid,"tenant_id",testTenant.getId())).result().granted()).isFalse();
    }

    @Test void scoped_save_rejects_missing_public_projection_before_creating_a_file() {
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        assertThatThrownBy(() -> withResources(accessor -> accessor.savePublicArtifact(fileField,"scoped.txt","text/plain",BYTES,Map.of())))
                .isInstanceOf(com.auraboot.framework.plugin.extension.DataAccessorException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
    }

    @Test void owner_derived_file_reference_does_not_require_a_technical_field_edit_grant() throws Exception {
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(fileField,secret),"writeFields",List.of())),
                testTenant.getId(),testRole.getId(),testTenant.getId(),permission);
        var saved = withResources(accessor -> {
            accessor.getById(model,rootPid); accessor.projectForPublicOutput(model,List.of(rootPid));
            return accessor.savePublicArtifact(fileField,"derived.txt","text/plain",BYTES,Map.of());
        });
        assertThat(fileService.findByPid(saved.fileId()).getArtifactAuthorization()).isNotBlank();
        assertThat(fileService.getFileRelations(saved.fileId())).hasSize(1);
    }

    @Test void scoped_save_rejects_a_hidden_root_file_reference_before_persistence() throws Exception {
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(secret),"writeFields",List.of())),
                testTenant.getId(),testRole.getId(),testTenant.getId(),permission);
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        assertThatThrownBy(() -> withResources(accessor -> {
            accessor.getById(model,rootPid); accessor.projectForPublicOutput(model,List.of(rootPid));
            return accessor.savePublicArtifact(fileField,"hidden.txt","text/plain",BYTES,Map.of());
        })).isInstanceOf(com.auraboot.framework.plugin.extension.DataAccessorException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
    }

    @Test void scoped_save_rejects_unknown_field_and_exceeded_budget_without_writing() {
        long count = jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId());
        for (boolean oversized : List.of(false,true)) {
            assertThatThrownBy(() -> withResources(accessor -> {
                accessor.getById(model,rootPid); accessor.projectForPublicOutput(model,List.of(rootPid));
                return accessor.savePublicArtifact(oversized ? fileField : secret,"budget.txt","text/plain",
                        oversized ? new byte[16_385] : BYTES,Map.of());
            })).isInstanceOf(com.auraboot.framework.plugin.extension.DataAccessorException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file WHERE tenant_id=?",Long.class,testTenant.getId())).isEqualTo(count);
    }

    @Test void scoped_save_cannot_outlive_the_handler_transaction() {
        var captured = new java.util.concurrent.atomic.AtomicReference<com.auraboot.framework.plugin.extension.DataAccessor>();
        withResources(accessor -> { captured.set(accessor); return null; });
        assertThatThrownBy(() -> captured.get().savePublicArtifact(fileField,"late.txt","text/plain",BYTES,Map.of()))
                .isInstanceOf(com.auraboot.framework.plugin.extension.DataAccessorException.class);
    }

    @Test void scoped_save_rolls_back_root_relation_record_and_bytes_when_later_business_work_fails() {
        var saved = new java.util.concurrent.atomic.AtomicReference<com.auraboot.framework.plugin.extension.FileAccessor.SavedFile>();
        var key = new java.util.concurrent.atomic.AtomicReference<String>();
        assertThatThrownBy(() -> withResources(accessor -> {
            accessor.getById(model,rootPid); accessor.projectForPublicOutput(model,List.of(rootPid));
            saved.set(accessor.savePublicArtifact(fileField,"scoped-rollback.txt","text/plain",BYTES,Map.of(secret,"PUBLISHED")));
            key.set(fileService.findByPid(saved.get().fileId()).getFileName());
            assertThat(fileService.getFileRelations(saved.get().fileId())).hasSize(1);
            throw new IllegalStateException("abort scoped artifact");
        })).isInstanceOf(IllegalStateException.class).hasMessage("abort scoped artifact");
        assertThat(fileService.findByPid(saved.get().fileId())).isNull();
        assertThat(storage.exists(key.get())).isFalse();
        assertThat(jdbc.queryForObject("SELECT "+fileField+" FROM "+table+" WHERE pid=?",String.class,rootPid)).isNull();
        assertThat(jdbc.queryForObject("SELECT "+secret+" FROM "+table+" WHERE pid=?",String.class,rootPid)).isEqualTo("BOARD");
    }
    @AfterEach void cleanup() {
        maskIds.forEach(masks::deleteConfig);
        if (commandId != null) commands.deleteById(commandId);
        if (table != null) { assertThat(table).matches("mt_artifact_[a-z0-9]+"); jdbc.execute("DROP TABLE IF EXISTS " + table); }
        if (modelId != null) { jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE model_id=? AND tenant_id=?",modelId,testTenant.getId()); models.deleteById(modelId); }
        fieldIds.forEach(fields::deleteById); metadata.clearAllCache();
    }
    FileAccessorImpl accessor() { return new FileAccessorImpl(fileService,storage,testUser.getId()); }
    void grantScope(String action, String scope) { scopes.setScope(testTenant.getId(),testRole.getId(),model,action,scope,"MAX"); }

    String useOnlyAlternativeDuty() throws Exception {
        String alternate = permission + ".alternative";
        grantCommittedPermissionToTestRole(alternate,"operation",model,"manage","Alternative artifact fixture");
        owner.duties = Set.of(permission, alternate);
        var entry = commands.selectById(commandId);
        var config = json.readValue(entry.getExecutionConfig(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        config.put("permissions",List.of(permission,alternate)); entry.setExecutionConfig(json.writeValueAsString(config));
        commands.updateById(entry);
        dutyStatus(permission,"inactive");
        return alternate;
    }
    void dutyStatus(String code,String status) {
        jdbc.update("UPDATE ab_role_permission SET status=? WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                status,testTenant.getId(),testRole.getId(),testTenant.getId(),code);
    }

    @Test void a_single_complete_alternative_allows_real_command_and_file_access() throws Exception {
        String alternate = useOnlyAlternativeDuty();
        assertThat(grants.resolveModelRead(testTenantMember.getId(),model,
                MetaContext.runWithCommandPermitPlan("TARGET",null,model,rootPid,() -> data.getByIdForAuthorization(model,rootPid)))
                .result().granted()).isFalse();
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Alternative command fixture");
        var request = new CommandExecuteRequest(); request.setTargetRecordId(rootPid); request.setOperationType("update");
        request.setClientRequestId(UniqueIdGenerator.generate()); request.setPayload(Map.of());
        var result = commandExecutor.execute(command,request);
        assertThat(result.getData()).containsEntry("success",true);
        String generated = result.getData().get("fileId").toString();
        try (var bytes = accessor().open(generated)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        grantScope("manage","none");
        assertThatThrownBy(() -> accessor().open(generated)).isInstanceOf(AccessDeniedException.class);
        grantScope("manage","all");
        dutyStatus(alternate,"inactive");
        assertThatThrownBy(() -> accessor().open(generated)).isInstanceOf(AccessDeniedException.class);
        dutyStatus(permission,"active");
        try (var bytes = accessor().open(generated)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
    }

    @Test void an_alternative_still_requires_visible_file_and_source_fields() throws Exception {
        String alternate = useOnlyAlternativeDuty();
        for (var readable : List.of(List.of(fileField),List.of(secret))) {
            jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                    json.writeValueAsString(Map.of("modelCode",model,"readFields",readable,"writeFields",List.of())),
                    testTenant.getId(),testRole.getId(),testTenant.getId(),alternate);
            assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
        }
        jdbc.update("UPDATE ab_role_permission SET field_scope=NULL WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                testTenant.getId(),testRole.getId(),testTenant.getId(),alternate);
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        var mask = new FieldMaskConfig(); mask.setModelCode(model); mask.setFieldCode(secret); mask.setEnabled(true);
        mask.setApplyToDetail(false); mask.setApplyToExport(true); mask.setMaskType("FULL"); mask.setReplacementChar("*");
        masks.saveConfig(mask); maskIds.add(mask.getId());
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void creator_reads_real_bytes_but_revoked_root_scope_blocks_known_file_id() throws Exception {
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
        grantScope("manage","none");
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessor().describe(filePid)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileService.getFileDownloadUrl(filePid)).isInstanceOf(AccessDeniedException.class);
        grantScope("manage","all");
        try (var bytes = accessor().open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
    }
    @Test void export_only_mask_and_hidden_file_reference_block_previously_generated_bytes() throws Exception {
        var mask = new FieldMaskConfig(); mask.setModelCode(model); mask.setFieldCode(secret); mask.setEnabled(true); mask.setApplyToDetail(false); mask.setApplyToExport(true);
        mask.setMaskType("FULL"); mask.setReplacementChar("*"); masks.saveConfig(mask); maskIds.add(mask.getId());
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
        masks.deleteConfig(mask.getId()); maskIds.remove(mask.getId());
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(secret),"writeFields",List.of())),testTenant.getId(),testRole.getId(),testTenant.getId(),permission);
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void a_complete_model_read_grant_allows_bytes_without_the_generation_duty() throws Exception {
        grantScope("manage","none");
        grantCommittedPermissionToTestRole("model."+model+".read","model",model,"read","Artifact reader fixture"); grantScope("read","all");
        var reader = new com.auraboot.framework.user.dao.entity.User(); reader.setPid(UniqueIdGenerator.generate());
        reader.setUserName("reader_"+model); reader.setEmail(reader.getPid().toLowerCase(Locale.ROOT)+"@fixture.invalid");
        reader.setNickName("Artifact reader"); reader.setPassword("unused-fixture-login"); reader.setCreatedAt(Instant.now()); reader.setUpdatedAt(Instant.now()); users.insert(reader);
        var member = members.addMember(reader.getId(),testTenant.getId(),"active");
        assertThat(roles.assignRolesToMember(member.getId(),List.of(testRole.getId()),testTenant.getId(),null)).isTrue();
        assertThat(fileService.findByPid(filePid).getCreatedBy()).isNotEqualTo(reader.getId());
        MetaContext.setContext(testTenant.getId(),reader.getId(),reader.getPid(),reader.getNickName()); MetaContext.setMemberId(member.getId());
        try {
            var readerAccessor = new FileAccessorImpl(fileService,storage,reader.getId());
            try (var bytes = readerAccessor.open(filePid)) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
            grantScope("read","none"); assertThatThrownBy(() -> readerAccessor.open(filePid)).isInstanceOf(AccessDeniedException.class);
        } finally {
            MetaContext.setContext(testTenant.getId(),testUser.getId(),testUser.getPid(),testUser.getNickName()); MetaContext.setMemberId(testTenantMember.getId());
        }
    }
    @Test void withdrawn_owner_definition_cannot_reuse_an_old_file() {
        jdbc.update("UPDATE ab_command_definition SET status='draft' WHERE tenant_id=? AND id=?",testTenant.getId(),commandId);
        assertThatThrownBy(() -> accessor().open(filePid)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void stored_provenance_cannot_be_cleared_replaced_or_rebound_to_another_storage_object() {
        for (String assignment : List.of("artifact_authorization=NULL", "artifact_authorization='{}'", "file_name='other.txt'", "file_size=999")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE ab_file SET " + assignment + " WHERE tenant_id=? AND pid=?",testTenant.getId(),filePid))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(fileService.findByPid(filePid).getArtifactAuthorization()).isNotBlank();
    }
    class Owner implements CommandHandlerExtension, BusinessResourceAuthorization {
        public String getCommandType() { return command; }
        final java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        boolean allowBindingMutation;
        Set<String> duties = Set.of(permission);
        public Object execute(CommandContext context) {
            executions.incrementAndGet();
            var db = context.dataAccessor(); db.getById(model,rootPid); db.projectForPublicOutput(model,List.of(rootPid));
            var saved = db.savePublicArtifact(fileField,"command-fixture.txt","text/plain",BYTES,Map.of());
            return Map.of("success",true,"fileId",saved.fileId());
        }
        public Boundary businessBoundary() { return new Boundary(command,model,"update",duties); }
        public Set<String> businessResourceModels() { return Set.of(model); }
        public Set<String> businessResultFields() { return Set.of("success","fileId"); }
        public Map<String,BusinessResultField> businessResultProjection() {
            return Map.of("success",BusinessResultField.constant(true),"fileId",BusinessResultField.rootField(fileField));
        }
        public Set<String> businessArtifactRootBindingFields() { return Set.of(projectField); }
        public Set<String> businessArtifactIntentBindingFields() { return Set.of(intentField); }
        public Map<String,Set<String>> publicBusinessResourceFields() { return Map.of(model,Set.of(fileField,secret)); }
        public Map<String,Integer> businessArtifactFields() { return Map.of(fileField,16_384); }
        public ReadIsolation resourceReadIsolation() { return ReadIsolation.REPEATABLE_READ; }
        public Grant authorizeResource(TargetAuthorizationContext root,Request request) {
            if (!model.equals(request.modelCode()) || !rootPid.equals(request.recordId()) || request.record() == null
                    || !rootPid.equals(request.record().get("pid"))) return null;
            if (request.action() != Action.READ && request.action() != Action.UPDATE) return null;
            return new Grant(Set.of("pid","tenant_id","row_version",fileField,secret,projectField),
                    allowBindingMutation ? Set.of(fileField,secret,projectField) : Set.of(fileField,secret),1);
        }
    }
}
