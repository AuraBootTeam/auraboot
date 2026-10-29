package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests verifying that {@link DynamicDataServiceImpl#getById}
 * fails closed (throws {@link MetaServiceException}) when the security gates
 * encounter an internal error, rather than silently returning the unmasked record.
 *
 * <p>This is the follow-up test class promised in commit
 * {@code fix(meta): make DynamicDataService.getById fail-secure on row-ACL /
 * field-mask exceptions}. The three security gates covered are:
 * <ol>
 *   <li>Row-level access check ({@code dataPermissionEngine.canAccessRecord}) — §P4</li>
 *   <li>Policy-driven column masking ({@code dataPermissionEngine.getFieldMaskRules}) — §P4</li>
 *   <li>Configurable detail-view masking ({@code fieldMaskService.applyMaskingForDetail}) — §P4</li>
 * </ol>
 *
 * <p>No Spring context / DB / Kafka is started. All dependencies are Mockito mocks
 * injected via constructor ({@code @RequiredArgsConstructor} on the SUT) through
 * {@code @InjectMocks}.
 *
 * <p>See {@code docs/standards/core/catch-exception-pattern.md} §P4 for the
 * project-wide fail-secure rule.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplGetByIdFailSecureTest {

    private static final String MODEL_CODE = "test_model";
    private static final String RECORD_ID = "rec-001";
    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 42L;
    private static final Long FALLBACK_MEMBER_ID = 4200L;

    // ---- all constructor dependencies as mocks ----
    @Mock private MetaModelService metadataService;
    @Mock private QueryBuilderService queryBuilderService;
    @Mock private ValidationService validationService;
    @Mock private NamedQueryService namedQueryService;
    @Mock private SecureSqlRewriter secureSqlRewriter;
    @Mock private TypeSystemManager typeSystemManager;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private SchemaManagementService schemaManagementService;
    @Mock private TableMetadataService tableMetadataService;
    @Mock private ObjectMapper objectMapper;
    @Mock private VirtualFieldEngine virtualFieldEngine;
    @Mock private ChangeTracker changeTracker;
    @Mock private UserMapper userMapper;
    @Mock private FileService fileService;
    @Mock private DataPermissionEngine dataPermissionEngine;
    @Mock private FieldMaskService fieldMaskService;
    @Mock private DataDomainService dataDomainService;
    @Mock private MetaModelMapper metaModelMapper;
    @Mock private ApplicationContext applicationContext;
    @Mock private PayloadTemporalNormalizer payloadTemporalNormalizer;
    @Mock private FieldPermissionService fieldPermissionService;
    @Mock private ExecutorRegistry executorRegistry;
    @Mock private PermissionFacade permissionFacade;
    @Mock private TenantMemberService tenantMemberService;

    @InjectMocks
    private DynamicDataServiceImpl service;

    /** Mock inner QueryBuilder returned by queryBuilderService. */
    @Mock private QueryBuilderService.QueryBuilder queryBuilder;

    @BeforeEach
    void setUpContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "pid-42", "tester");
        MetaContext.setMemberId(USER_ID);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    /**
     * Wire up the common happy-path stubs so that the record is found in the DB
     * and the test can reach the security gate under test.
     *
     * <p>Stubs covered here:
     * <ul>
     *   <li>{@code executorRegistry.resolve} → empty (use physical path)</li>
     *   <li>{@code metadataService.getModelDefinition} → minimal ModelDefinition</li>
     *   <li>{@code metadataService.getPrimaryKeyField} → minimal pk FieldDefinition</li>
     *   <li>{@code queryBuilderService.buildConditionQuery} → mock QueryBuilder</li>
     *   <li>{@code queryBuilder.getSql/getParameterMap} → dummy values</li>
     *   <li>{@code dynamicDataMapper.selectByQuery} → one-record result</li>
     * </ul>
     */
    private void wireHappyPathDbStubs() {
        // Virtual-model executor: not present → fall through to physical path
        when(executorRegistry.resolve(any())).thenReturn(Optional.empty());

        // Model definition
        FieldDefinition pk = FieldDefinition.builder()
                .code("pid")
                .columnName("pid")
                .build();
        ModelDefinition model = ModelDefinition.builder()
                .code(MODEL_CODE)
                .tableName("mt_" + MODEL_CODE)
                .sourceType("physical")
                .fields(List.of(pk))
                .build();
        when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        when(metadataService.getPrimaryKeyField(MODEL_CODE)).thenReturn(pk);

        // QueryBuilder stub
        when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        when(queryBuilder.getSql()).thenReturn("SELECT * FROM mt_test_model WHERE pid = :pid AND tenant_id = :tenant_id");
        when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid", RECORD_ID, "tenant_id", TENANT_ID));

        // DB returns one record
        Map<String, Object> rawRecord = Map.of("pid", RECORD_ID, "name", "secret-value");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(rawRecord));
    }

    private void wireRuleCenterPermissionAllows() {
        when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        when(permissionFacade.canOperate(eq(USER_ID), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(PermissionResult.allow(List.of(
                        new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok"))));
    }

    private static final String CANONICAL_PID=RequiredReadReferencesTest.id(900);
    private DynamicDataService wireCanonicalClosure(
            java.util.function.UnaryOperator<Map<String,Object>> mask,
            java.util.function.Predicate<Map<String,Object>> maskedScope) {
        wireHappyPathDbStubs();wireRuleCenterPermissionAllows();
        ModelDefinition model=metadataService.getModelDefinition(MODEL_CODE).orElseThrow();
        var declaration=RequiredReadReferencesTest.model(10);model.setImmutable(true);
        model.setFields(declaration.getFields());model.setExtension(declaration.getExtension());
        when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid",CANONICAL_PID,"tenant_id",TENANT_ID));
        when(dynamicDataMapper.selectByQuery(anyString(),anyMap())).thenAnswer(call -> List.of(new java.util.LinkedHashMap<>(
                Map.of("pid",CANONICAL_PID,"tenant_id",TENANT_ID,"members",new java.util.ArrayList<>(List.of(RequiredReadReferencesTest.id(1))),"team","visible"))));
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE),anyMap(),eq(USER_ID))).thenAnswer(call -> mask.apply(call.getArgument(1)));
        lenient().when(fieldPermissionService.getFieldPermissions(USER_ID,MODEL_CODE)).thenReturn(
                com.auraboot.framework.permission.engine.model.FieldPermissionSet.allAllowed(java.util.Set.of("pid","tenant_id","members","team")));
        DynamicDataService dependencies=mock(DynamicDataService.class);
        when(permissionFacade.canAction(USER_ID,"fixture_member","read")).thenReturn(true);
        when(dependencies.getById(eq("fixture_member"),anyString())).thenAnswer(call -> Map.of("pid",call.getArgument(1)));
        var guard=new RequiredRecordReadGuard(metadataService,dependencies,permissionFacade,queryBuilderService,dynamicDataMapper,dataDomainService);
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap())).thenAnswer(call -> {
            guard.require(MODEL_CODE,call.getArgument(3));return true;
        });
        lenient().when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq("read"),eq(USER_ID),anyMap())).thenAnswer(call -> {
            Map<String,Object> row=call.getArgument(4);if(!maskedScope.test(row))return false;
            try {guard.require(MODEL_CODE,row);return true;}
            catch(org.springframework.security.access.AccessDeniedException denied) {return false;}
        });
        return dependencies;
    }
    private void authorizeCanonicalRead() {
        var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,
                row -> helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row));
    }
    @Test void sameCanonicalReadKeepsMaskedRootCheckButReadsItsDependencyOnce() {
        var dependencies=wireCanonicalClosure(row -> row,row -> row.containsKey("team"));
        authorizeCanonicalRead();
        verify(dataPermissionEngine).canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap());
        verify(dataPermissionEngine).canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq("read"),eq(USER_ID),anyMap());
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void sameCanonicalReadCannotBypassAMaskedRootScopeDenial() {
        var dependencies=wireCanonicalClosure(row -> {row.remove("team");return row;},row -> row.containsKey("team"));
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(MetaServiceException.class).hasMessageContaining("Access denied");
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
        verify(dataPermissionEngine).canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq("read"),eq(USER_ID),anyMap());
    }
    @Test void canonicalReadReauthorizesAnInPlaceReferenceReplacement() {
        var dependencies=wireCanonicalClosure(row -> {
            row.put("members","[\""+RequiredReadReferencesTest.id(2)+"\"]");return row;
        },row -> true);
        authorizeCanonicalRead();
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(2));
    }
    @Test void canonicalReadRejectsADeniedReplacementInsteadOfReusingTheOldReference() {
        var dependencies=wireCanonicalClosure(row -> {
            row.put("members","[\""+RequiredReadReferencesTest.id(2)+"\"]");return row;
        },row -> true);
        doThrow(new org.springframework.security.access.AccessDeniedException("replacement revoked")).when(dependencies)
                .getById("fixture_member",RequiredReadReferencesTest.id(2));
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(MetaServiceException.class).hasMessageContaining("Access denied");
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(2));
    }
    @Test void canonicalReadRejectsMissingMaskedReferences() {
        var dependencies=wireCanonicalClosure(row -> {row.remove("members");return row;},row -> true);
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(MetaServiceException.class).hasMessageContaining("Access denied");
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadRejectsAMissingMaskedTenant() {
        var dependencies=wireCanonicalClosure(row -> {row.remove("tenant_id");return row;},row -> true);
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(MetaServiceException.class).hasMessageContaining("Access denied");
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadNeverReusesItsWitnessForTheNextOperation() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);authorizeCanonicalRead();
        doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(dependencies)
                .getById("fixture_member",RequiredReadReferencesTest.id(1));
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadWitnessCanOnlyAvoidOneFinalTraversal() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
        });
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void anIndependentLoaderInsideFinalAuthorizationCannotBorrowTheWitness() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(dependencies)
                    .getById("fixture_member",RequiredReadReferencesTest.id(1));
            assertThatThrownBy(()->service.getByIdForAuthorization(MODEL_CODE,CANONICAL_PID))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row))
                    .isInstanceOf(MetaServiceException.class);
        });
    }
    @Test void anAllowedIndependentLoaderStillPerformsItsOwnAndTheFinalDependencyChecks() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            service.getByIdForAuthorization(MODEL_CODE,CANONICAL_PID);
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
        });
        verify(dependencies,times(3)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadRechecksAfterAnObservedMutation() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            DynamicDataQueryScope.invalidateFiltersAfterMutation();
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
        });
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadCannotReuseADifferentActor() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        assertThatThrownBy(()->service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            MetaContext.setMemberId(999L);
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
        })).isInstanceOf(MetaServiceException.class).hasMessageContaining("Access denied");
        verify(dependencies).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadRechecksWhenItsPolicyChanges() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            metadataService.getModelDefinition(MODEL_CODE).orElseThrow().setExtension(RequiredReadReferencesTest.model(20).getExtension());
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
        });
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadMaskFailureCannotCompleteOrLeakAWitness() {
        var dependencies=wireCanonicalClosure(row -> {throw new IllegalStateException("mask unavailable");},row -> true);
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> called.set(true)))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("Failed to load record");
        org.assertj.core.api.Assertions.assertThat(called.get()).isFalse();
        doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(dependencies)
                .getById("fixture_member",RequiredReadReferencesTest.id(1));
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void canonicalReadFinalAuthorizerFailureDoesNotLeakAWitness() {
        var dependencies=wireCanonicalClosure(row -> row,row -> true);var helper=new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
        assertThatThrownBy(()->service.withCanonicalRecordRead(MODEL_CODE,CANONICAL_PID,row -> {
            helper.authorizeRecordId(MODEL_CODE,"read",CANONICAL_PID,ignored -> row);
            throw new IllegalStateException("final failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("final failed");
        doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(dependencies)
                .getById("fixture_member",RequiredReadReferencesTest.id(1));
        assertThatThrownBy(this::authorizeCanonicalRead).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
    }
    @Test void executorEarlyReturnCannotCompleteAPhysicalCanonicalWitness() {
        var model=RequiredReadReferencesTest.model(10);model.setCode(MODEL_CODE);
        when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        var executor=mock(com.auraboot.framework.meta.service.executor.ModelDataExecutor.class);
        when(executorRegistry.resolve(any())).thenReturn(Optional.of(executor));
        when(executor.get(MODEL_CODE,CANONICAL_PID)).thenReturn(new java.util.LinkedHashMap<>(
                Map.of("pid",CANONICAL_PID,"tenant_id",TENANT_ID,"members",List.of(RequiredReadReferencesTest.id(1)))));
        var dependencies=mock(DynamicDataService.class);when(dependencies.getById(eq("fixture_member"),anyString())).thenReturn(Map.of("pid",RequiredReadReferencesTest.id(1)));
        when(permissionFacade.canAction(USER_ID,"fixture_member","read")).thenReturn(true);
        var guard=new RequiredRecordReadGuard(metadataService,dependencies,permissionFacade,queryBuilderService,dynamicDataMapper,dataDomainService);
        doAnswer(call -> {guard.require(MODEL_CODE,call.getArgument(1));return null;}).when(dataPermissionEngine).requireRecordReads(eq(MODEL_CODE),anyMap());
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq("read"),eq(USER_ID),anyMap()))
                .thenAnswer(call -> {guard.require(MODEL_CODE,call.getArgument(4));return true;});
        authorizeCanonicalRead();verify(dependencies,times(2)).getById("fixture_member",RequiredReadReferencesTest.id(1));
        verify(dataPermissionEngine,never()).canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap());
        verifyNoInteractions(fieldMaskService,fieldPermissionService);
    }

    private TenantMember tenantMember(Long memberId) {
        TenantMember member = new TenantMember();
        member.setId(memberId);
        member.setTenantId(TENANT_ID);
        member.setUserId(USER_ID);
        member.setStatus("ACTIVE");
        return member;
    }

    @Test
    void dependencyReadsKeepAuthorizationAndMaskingButDoNotExpandDisplayBackLinks() {
        wireHappyPathDbStubs();wireRuleCenterPermissionAllows();
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE),anyMap(),eq(USER_ID))).thenAnswer(call -> call.getArgument(1));
        when(fieldPermissionService.getFieldPermissions(USER_ID,MODEL_CODE)).thenReturn(com.auraboot.framework.permission.engine.model.FieldPermissionSet.allAllowed(java.util.Set.of("pid","name","related")));
        ModelDefinition model=metadataService.getModelDefinition(MODEL_CODE).orElseThrow();
        FieldDefinition ref=FieldDefinition.builder().code("related").columnName("related").dataType("reference").build();
        ref.setExtraProps(Map.of("refTarget",Map.of("targetEntity","fixture_policy","valueField","pid","displayField","name")));
        model.setFields(List.of(model.getFields().getFirst(),ref));
        when(dynamicDataMapper.selectByQuery(anyString(),anyMap())).thenAnswer(call -> List.of(new java.util.LinkedHashMap<>(Map.of("pid",RECORD_ID,"name","secret-value","related","policy-1"))));
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap())).thenReturn(true);
        var dependencyModels=mock(MetaModelService.class);var dependencyData=mock(DynamicDataService.class);
        var dependencyPermissions=mock(PermissionFacade.class);
        when(dependencyModels.getModelDefinition("fixture_bundle")).thenReturn(Optional.of(RequiredReadReferencesTest.model(10)));
        when(dependencyPermissions.canAction(USER_ID,"fixture_member","read")).thenReturn(true);
        var guard=new RequiredRecordReadGuard(dependencyModels,dependencyData,dependencyPermissions,queryBuilderService,dynamicDataMapper,dataDomainService);
        when(dependencyData.getById(eq("fixture_member"),anyString())).thenAnswer(call -> service.getById(MODEL_CODE,RECORD_ID));
        guard.require("fixture_bundle",Map.of("pid",RequiredReadReferencesTest.id(9),"tenant_id",TENANT_ID,"members",List.of(RequiredReadReferencesTest.id(1))));
        verify(permissionFacade,never()).canAction(USER_ID,"fixture_policy","read");
        verify(permissionFacade).canOperate(eq(USER_ID),eq(MODEL_CODE),eq("read"),anyMap());
        verify(dataPermissionEngine).canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap());
        verify(dataPermissionEngine).getFieldMaskRules(TENANT_ID,MODEL_CODE,USER_ID);
        service.getById(MODEL_CODE,RECORD_ID);
        verify(permissionFacade).canAction(USER_ID,"fixture_policy","read");
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID),eq(MODEL_CODE),eq(USER_ID),anyMap())).thenReturn(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.require("fixture_bundle",Map.of("pid",RequiredReadReferencesTest.id(9),"tenant_id",TENANT_ID,"members",List.of(RequiredReadReferencesTest.id(1)))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThat(RequiredRecordReadGuard.isResolvingReference()).isFalse();
    }

    @Test
    void explicitAuthorizationReadKeepsGatesAndMaskedShapeWithoutReferencePresentation() {
        wireHappyPathDbStubs();
        wireRuleCenterPermissionAllows();
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap())).thenReturn(true);
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID))).thenAnswer(call -> call.getArgument(1));
        when(fieldPermissionService.getFieldPermissions(USER_ID, MODEL_CODE)).thenReturn(
                new com.auraboot.framework.permission.engine.model.FieldPermissionSet(java.util.Set.of("pid", "related"), java.util.Set.of("pid", "related"), java.util.Set.of("name")));
        var model = metadataService.getModelDefinition(MODEL_CODE).orElseThrow();
        var related = FieldDefinition.builder().code("related").columnName("related").dataType("reference").build();
        related.setExtraProps(Map.of("refTarget", Map.of("targetEntity", "fixture_policy", "valueField", "pid", "displayField", "name")));
        model.setFields(List.of(model.getFields().getFirst(), related));
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenAnswer(call -> List.of(
                new java.util.LinkedHashMap<>(Map.of("pid", RECORD_ID, "name", "secret-value", "related", "policy-1"))));

        var record = service.getByIdForAuthorization(MODEL_CODE, RECORD_ID);
        org.assertj.core.api.Assertions.assertThat(record).containsEntry("pid", RECORD_ID).containsEntry("related", "policy-1").doesNotContainKey("name");
        verify(permissionFacade).canOperate(eq(USER_ID), eq(MODEL_CODE), eq("read"), anyMap());
        verify(dataPermissionEngine).canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap());
        verify(dataPermissionEngine).getFieldMaskRules(TENANT_ID, MODEL_CODE, USER_ID);
        verify(fieldMaskService).applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID));
        verify(fieldPermissionService).getFieldPermissions(USER_ID, MODEL_CODE);
        verify(permissionFacade, never()).canAction(USER_ID, "fixture_policy", "read");
        service.getById(MODEL_CODE, RECORD_ID);
        verify(permissionFacade).canAction(USER_ID, "fixture_policy", "read");
    }

    @Test
    void explicitAuthorizationReadStillRejectsDeniedRows() {
        wireHappyPathDbStubs();
        wireRuleCenterPermissionAllows();
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap())).thenReturn(false);
        assertThatThrownBy(() -> service.getByIdForAuthorization(MODEL_CODE, RECORD_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(fieldMaskService);
    }

    @Test
    void explicitAuthorizationReadDoesNotReturnUnmaskedDataWhenMaskingFails() {
        wireHappyPathDbStubs();
        wireRuleCenterPermissionAllows();
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap())).thenReturn(true);
        when(dataPermissionEngine.getFieldMaskRules(TENANT_ID, MODEL_CODE, USER_ID)).thenThrow(new IllegalStateException("mask lookup failed"));
        assertThatThrownBy(() -> service.getByIdForAuthorization(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("Field masking evaluation failed");
    }

    // =====================================================================
    // Test (a): row-ACL check throws → getById must NOT return the record
    // =====================================================================

    @Test
    @DisplayName("row-ACL engine throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_rowAclEngineThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();
        wireRuleCenterPermissionAllows();

        // Simulate an internal error in the permission engine (e.g. DB timeout, NPE in policy eval)
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenThrow(new RuntimeException("permission engine DB timeout"));

        // The method must throw MetaServiceException — NOT return the record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Data permission evaluation failed");
    }

    @Test
    @DisplayName("row-ACL engine returns false → getById throws coded access denial")
    void getById_rowAclDenied_throwsAccessDeniedException() {
        wireHappyPathDbStubs();
        wireRuleCenterPermissionAllows();

        // Explicit deny (policy says this user may not see this row)
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    // =====================================================================
    // Test (b): policy-driven field masking throws → must NOT return unmasked fields
    // =====================================================================

    @Test
    @DisplayName("getFieldMaskRules throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_maskRulesLookupThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        wireRuleCenterPermissionAllows();

        // Simulate internal error while fetching masking rules
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenThrow(new RuntimeException("field mask policy load error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field masking evaluation failed");
    }

    @Test
    @DisplayName("applyFieldMasking throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_applyFieldMaskingThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        wireRuleCenterPermissionAllows();

        // Masking rules are loaded successfully...
        List<FieldMaskRule> rules = List.of(mock(FieldMaskRule.class));
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(rules);

        // ...but applyFieldMasking itself throws
        when(dataPermissionEngine.applyFieldMasking(anyList(), eq(rules)))
                .thenThrow(new RuntimeException("masking serialization error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field masking evaluation failed");
    }

    // =====================================================================
    // Test (b2): field-permission filter lookup throws → must fail closed
    // (deep-review DR-20260701 W1-F2 — the two applyFieldPermissionFilter
    //  helpers used to log.warn + return the UNMASKED record)
    // =====================================================================

    @Test
    @DisplayName("getFieldPermissions throws → getById throws MetaServiceException (fail-secure, field-permission gate)")
    void getById_fieldPermissionLookupThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL + policy/configurable masking all pass, so control reaches the field-permission filter
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        wireRuleCenterPermissionAllows();
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Field-permission evaluation blows up (e.g. role lookup DB error / missing member context)
        when(fieldPermissionService.getFieldPermissions(any(), eq(MODEL_CODE)))
                .thenThrow(new RuntimeException("field permission role lookup error"));

        // Must NOT return the record with its hidden fields still present
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field permission evaluation failed");
    }

    // =====================================================================
    // Test (c): configurable detail masking (fieldMaskService) throws
    // =====================================================================

    @Test
    @DisplayName("fieldMaskService.applyMaskingForDetail throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_detailMaskServiceThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        wireRuleCenterPermissionAllows();

        // Policy-driven masking: no rules to apply (safe to skip that step)
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());

        // configurable masking step throws
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenThrow(new RuntimeException("configurable masking config error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Detail-view field masking failed");
    }

    // =====================================================================
    // Happy path sanity — ensures the stubs compose correctly and the
    // gate is in place but not incorrectly always throwing
    // =====================================================================

    @Test
    @DisplayName("all security gates pass → getById returns the record (no false denial)")
    void getById_allGatesPass_returnsRecord() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        wireRuleCenterPermissionAllows();

        // No policy mask rules
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());

        // Configurable masking: return the record unchanged
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Field permission: no hidden fields
        when(fieldPermissionService.getFieldPermissions(any(), eq(MODEL_CODE)))
                .thenReturn(new com.auraboot.framework.permission.engine.model.FieldPermissionSet(
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));

        Map<String, Object> result = service.getById(MODEL_CODE, RECORD_ID);
        org.assertj.core.api.Assertions.assertThat(result).containsKey("pid");
    }

    @Test
    @DisplayName("Rule Center permission facade returns DENY → getById throws before masking")
    void getById_ruleCenterPermissionDenies_throwsBeforeMasking() {
        wireHappyPathDbStubs();

        when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        when(permissionFacade.canOperate(eq(USER_ID), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(PermissionResult.deny(
                        "Condition guard not satisfied",
                        List.of(new EvaluationStep("Policy", EvaluationVerdict.DENY, "Condition guard not satisfied"))));

        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verify(dataPermissionEngine, never()).canAccessRecord(anyLong(), anyString(), anyLong(), anyMap());
        verify(dataPermissionEngine, never()).getFieldMaskRules(anyLong(), anyString(), anyLong());
        verify(fieldMaskService, never()).applyMaskingForDetail(anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("Rule Center DENY runs before legacy row ACL so audit-capable permission pipeline is not bypassed")
    void getById_ruleCenterDenyPrecedesLegacyRowAcl() {
        wireHappyPathDbStubs();

        when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        when(permissionFacade.canOperate(eq(USER_ID), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(PermissionResult.deny(
                        "Condition guard not satisfied",
                        List.of(new EvaluationStep("Policy", EvaluationVerdict.DENY, "Condition guard not satisfied"))));

        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verify(permissionFacade).canOperate(eq(USER_ID), eq(MODEL_CODE), eq("read"), anyMap());
        verify(dataPermissionEngine, never()).canAccessRecord(anyLong(), anyString(), anyLong(), anyMap());
    }

    @Test
    @DisplayName("Rule Center permission resolves tenant member id when MetaContext memberId is missing")
    void getById_ruleCenterPermissionResolvesTenantMemberWhenContextMemberMissing() {
        wireHappyPathDbStubs();
        MetaContext.setMemberId(null);

        when(applicationContext.getBean(TenantMemberService.class)).thenReturn(tenantMemberService);
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, USER_ID))
                .thenReturn(tenantMember(FALLBACK_MEMBER_ID));
        when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        when(permissionFacade.canOperate(eq(FALLBACK_MEMBER_ID), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(PermissionResult.deny(
                        "Condition guard not satisfied",
                        List.of(new EvaluationStep("Policy", EvaluationVerdict.DENY, "Condition guard not satisfied"))));

        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verify(permissionFacade).canOperate(eq(FALLBACK_MEMBER_ID), eq(MODEL_CODE), eq("read"), anyMap());
        verify(dataPermissionEngine, never()).canAccessRecord(anyLong(), anyString(), anyLong(), anyMap());
    }
    @Test void commandAllPermitCannotSkipRequiredMemberReadRestriction() {
        wireHappyPathDbStubs();
        doThrow(new org.springframework.security.access.AccessDeniedException("member revoked"))
                .when(dataPermissionEngine).requireRecordReads(eq(MODEL_CODE),anyMap());
        MetaContext.runWithCommandPermitScope("ALL",()->{
            assertThatThrownBy(()->service.getById(MODEL_CODE,RECORD_ID))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessage("member revoked");
        });
        verify(dataPermissionEngine).requireRecordReads(eq(MODEL_CODE),anyMap());
        verify(dataPermissionEngine,never()).canAccessRecord(anyLong(),anyString(),anyLong(),anyMap());
    }
}
