package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.release.ApplicationPageDefinitionReadService;
import com.auraboot.framework.application.release.PageDefinitionShadowReadService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringRuntimePageMaterializer;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.converter.PageSchemaConverter;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaListDTO;
import com.auraboot.framework.meta.dto.PageSchemaRuntimeDTO;
import com.auraboot.framework.meta.dto.PageSchemaSyncVersionDTO;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.dto.PaginationRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.event.SchemaPublishedEvent;
import com.auraboot.framework.application.release.ApplicationDefinitionMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.contribution.PageSchemaContributionComposer;
import com.auraboot.framework.meta.contribution.PageSchemaContributionProvider;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.validator.PageSchemaAuthoringProfileValidator;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PageSchemaServiceImpl 分支覆盖单元测试（无 DB）：创建/更新/删除守卫、
 * 发布与版本语义、运行时组合（materialize → contributions → system tabs）、
 * 定义影子/主读开关，以及移动端同步版本装配。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PageSchemaServiceImpl 分支覆盖")
class PageSchemaServiceImplTest {

    @Mock
    private PageSchemaAuthoringProfileValidator renderProfiles;
    @Mock
    private PageSchemaMapper pageSchemaMapper;
    @Mock
    private PageSchemaConverter pageSchemaConverter;
    @Mock
    private com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock
    private MetaModelMapper metaModelMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private com.auraboot.framework.meta.service.impl.PageSchemaDefaultBlockGenerator defaultBlockGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private AuthoringRuntimePageMaterializer authoringRuntimePageMaterializer;
    @Mock
    private PageSchemaContributionComposer pageSchemaContributionComposer;
    @Mock
    private ObjectProvider<PageSchemaContributionProvider> contributionProviderProvider;

    private ApplicationPageDefinitionReadService applicationPageDefinitionReadService;
    private PageDefinitionShadowReadService pageDefinitionShadowReadService;

    @InjectMocks
    private PageSchemaServiceImpl service;

    private PageSchema entity;

    @BeforeEach
    void setUp() {
        MetaContext.setCurrentTenantId(1L);
        entity = pageSchema();
        // Runtime composition is a pass-through by default; the injection and
        // contribution branches open these up explicitly.
        when(authoringRuntimePageMaterializer.materialize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pageSchemaContributionComposer.compose(any(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(contributionProviderProvider.getIfAvailable(any()))
                .thenReturn(PageSchemaContributionProvider.none());
        when(pageSchemaConverter.toEntity(any(PageSchemaCreateRequest.class))).thenAnswer(inv -> {
            PageSchemaCreateRequest request = inv.getArgument(0);
            PageSchema schema = pageSchema();
            schema.setPageKey(request.getPageKey());
            schema.setName(request.getName());
            schema.setKind(request.getKind());
            schema.setModelCode(request.getModelCode());
            schema.setBlocks(request.getBlocks() == null ? null
                    : objectMapper.writeValueAsString(request.getBlocks()));
            return schema;
        });
        when(pageSchemaConverter.toDTO(any(PageSchema.class)))
                .thenAnswer(inv -> dto(inv.getArgument(0)));

        applicationPageDefinitionReadService = new ApplicationPageDefinitionReadService(
                org.mockito.Mockito.mock(ApplicationDefinitionMapper.class),
                org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationDefinitionResolver.class),
                objectMapper);
        pageDefinitionShadowReadService = new PageDefinitionShadowReadService(
                org.mockito.Mockito.mock(ApplicationDefinitionMapper.class),
                org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationDefinitionResolver.class),
                objectMapper,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "pageDefinitionShadowReadService", pageDefinitionShadowReadService);
        ReflectionTestUtils.setField(service, "applicationPageDefinitionReadService", applicationPageDefinitionReadService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private PageSchema pageSchema() {
        PageSchema schema = new PageSchema();
        schema.setId(1L);
        schema.setPid("pid-1");
        schema.setTenantId(1L);
        schema.setPageKey("home");
        schema.setName("home-page");
        schema.setTitle("Home");
        schema.setKind("detail");
        schema.setStatus(Status.DRAFT.getCode());
        schema.setVersion(1);
        schema.setDeletedFlag(false);
        schema.setCreatedAt(Instant.now());
        schema.setUpdatedAt(Instant.now());
        return schema;
    }

    private PageSchemaDTO dto(PageSchema source) {
        PageSchemaDTO dto = new PageSchemaDTO();
        dto.setPid(source.getPid());
        dto.setPageKey(source.getPageKey());
        dto.setName(source.getName());
        dto.setKind(source.getKind());
        dto.setStatus(source.getStatus());
        dto.setVersion(source.getVersion());
        return dto;
    }

    private Map<String, Object> block(String id, String type) {
        Map<String, Object> block = new HashMap<>();
        block.put("id", id);
        block.put("blockType", type);
        return block;
    }

    private PageSchemaCreateRequest createRequest() {
        PageSchemaCreateRequest request = new PageSchemaCreateRequest();
        request.setPageKey("landing");
        request.setName("landing-page");
        request.setKind("list");
        request.setBlocks(List.of(block("b1", "form")));
        return request;
    }

    // ==================== create ====================

    @Nested
    @DisplayName("创建")
    class Create {

        @Test
        @DisplayName("缺 pageKey/name/kind 分别拒绝")
        void blankRequiredFieldsRejected() {
            PageSchemaCreateRequest request = createRequest();
            request.setPageKey(" ");
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面Key");

            PageSchemaCreateRequest noName = createRequest();
            noName.setName("");
            assertThatThrownBy(() -> service.create(noName))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面名称");

            PageSchemaCreateRequest noKind = createRequest();
            noKind.setKind("");
            assertThatThrownBy(() -> service.create(noKind))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("kind");
        }

        @Test
        @DisplayName("pageKey 或 name 冲突拒绝")
        void duplicatesRejected() {
            when(pageSchemaMapper.countByPageKey("landing", null)).thenReturn(1L);
            assertThatThrownBy(() -> service.create(createRequest()))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面Key已存在");

            when(pageSchemaMapper.countByPageKey("landing", null)).thenReturn(0L);
            when(pageSchemaMapper.countByName("landing-page", null)).thenReturn(2L);
            assertThatThrownBy(() -> service.create(createRequest()))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面名称已存在");
        }

        @Test
        @DisplayName("非法 blocks（超大 payload）拒绝")
        void oversizedBlocksRejected() {
            PageSchemaCreateRequest request = createRequest();
            request.setBlocks(List.of(Map.of("id", "b1", "blockType", "form",
                    "blob", "x".repeat(520 * 1024))));
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("Blocks format is invalid");
        }

        @Test
        @DisplayName("绑定模型且未给 blocks 时生成默认块")
        void defaultBlocksGeneratedForBoundModel() {
            PageSchemaCreateRequest request = createRequest();
            request.setBlocks(null);
            request.setModelCode("crm_customer");
            when(pageSchemaMapper.countByPageKey("landing", null)).thenReturn(0L);
            when(pageSchemaMapper.countByName("landing-page", null)).thenReturn(0L);
            when(defaultBlockGenerator.generate("list", "crm_customer"))
                    .thenReturn(List.of(block("gen-1", "table")));

            service.create(request);

            ArgumentCaptor<PageSchema> captor = ArgumentCaptor.forClass(PageSchema.class);
            verify(pageSchemaMapper).insert(captor.capture());
            assertThat(captor.getValue().getBlocks()).isNotEmpty();
            assertThat(captor.getValue().getStatus()).isEqualTo(Status.DRAFT.getCode());
            assertThat(captor.getValue().getVersion()).isEqualTo(1);
            assertThat(captor.getValue().getIsCurrent()).isTrue();
        }

        @Test
        @DisplayName("绑定模型的 boundModelVersion 快照进 extension；未知模型跳过")
        void boundModelVersionSnapshot() {
            PageSchemaCreateRequest request = createRequest();
            request.setModelCode("crm_customer");
            when(pageSchemaMapper.countByPageKey(anyString(), any())).thenReturn(0L);
            when(pageSchemaMapper.countByName(anyString(), any())).thenReturn(0L);

            ModelDefinition definition = ModelDefinition.builder()
                    .code("crm_customer").version(7).build();
            when(metaModelService.getDefinitionByCode("crm_customer")).thenReturn(definition);

            service.create(request);

            ArgumentCaptor<PageSchema> captor = ArgumentCaptor.forClass(PageSchema.class);
            verify(pageSchemaMapper).insert(captor.capture());
            assertThat(captor.getValue().getExtension().getExtension())
                    .containsEntry("boundModelVersion", "crm_customer@7");

            // Unknown model: save still succeeds without the snapshot.
            PageSchemaCreateRequest unknown = createRequest();
            unknown.setPageKey("landing-2");
            unknown.setName("landing-page-2");
            unknown.setModelCode("ghost_model");
            when(metaModelService.getDefinitionByCode("ghost_model"))
                    .thenThrow(new IllegalStateException("not found"));
            service.create(unknown);
        }

        @Test
        @DisplayName("自动权限分配失败不阻断创建")
        void permissionFailureIsNonBlocking() {
            when(pageSchemaMapper.countByPageKey(anyString(), any())).thenReturn(0L);
            when(pageSchemaMapper.countByName(anyString(), any())).thenReturn(0L);
            org.mockito.Mockito.doThrow(new IllegalStateException("rbac down"))
                    .when(autoPermissionAssignmentService)
                    .autoAssignPermissions(anyString(), any());

            PageSchemaDTO created = service.create(createRequest());

            assertThat(created).isNotNull();
            verify(pageSchemaMapper).insert(any(PageSchema.class));
        }
    }

    // ==================== update / delete ====================

    @Nested
    @DisplayName("更新与删除")
    class UpdateAndDelete {

        @Test
        @DisplayName("乐观锁版本冲突拒绝；匹配时递增 rowVersion")
        void optimisticLockEnforced() {
            entity.setRowVersion(3);
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);

            PageSchemaUpdateRequest stale = new PageSchemaUpdateRequest();
            stale.setRowVersion(2);
            assertThatThrownBy(() -> service.update("pid-1", stale))
                    .isInstanceOf(com.auraboot.framework.exception.ConflictException.class)
                    .hasMessageContaining("Version conflict");

            PageSchemaUpdateRequest fresh = new PageSchemaUpdateRequest();
            fresh.setRowVersion(3);
            when(pageSchemaConverter.toDTO(any(PageSchema.class)))
                    .thenAnswer(inv -> dto(entity));
            service.update("pid-1", fresh);
            assertThat(entity.getRowVersion()).isEqualTo(4);
            verify(pageSchemaMapper).updateById(entity);
        }

        @Test
        @DisplayName("改 pageKey/name 时重新校验唯一性")
        void renameTriggersUniqueness() {
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            when(pageSchemaMapper.countByPageKey("other-key", "pid-1")).thenReturn(1L);

            PageSchemaUpdateRequest rename = new PageSchemaUpdateRequest();
            rename.setPageKey("other-key");
            assertThatThrownBy(() -> service.update("pid-1", rename))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面Key已存在");

            PageSchemaUpdateRequest ok = new PageSchemaUpdateRequest();
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(entity));
            service.update("pid-1", ok);
            verify(pageSchemaMapper).updateById(entity);
        }

        @Test
        @DisplayName("已发布不可删，草稿可删")
        void deleteGuards() {
            entity.setStatus(Status.PUBLISHED.getCode());
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            assertThatThrownBy(() -> service.delete("pid-1"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("已发布");

            entity.setStatus(Status.DRAFT.getCode());
            service.delete("pid-1");
            verify(pageSchemaMapper).deleteById(entity.getId());
        }

        @Test
        @DisplayName("PID 空或不存在拒绝")
        void missingPidRejected() {
            assertThatThrownBy(() -> service.findByPid(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("PID");
            when(pageSchemaMapper.selectByPid("ghost")).thenReturn(null);
            assertThatThrownBy(() -> service.findByPid("ghost"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面配置不存在");
        }
    }

    // ==================== publish / unpublish / versions ====================

    @Nested
    @DisplayName("发布与版本")
    class PublishAndVersions {

        @Test
        @DisplayName("发布：状态翻转 + 事件（schemaVersion 空回退 1）")
        void publishFlipsStatusAndEmitsEvent() {
            entity.setStatus(Status.DRAFT.getCode());
            entity.setBlocks("[{\"id\":\"b1\",\"blockType\":\"form\"}]");
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(entity));

            service.publish("pid-1");

            assertThat(entity.getStatus()).isEqualTo(Status.PUBLISHED.getCode());
            assertThat(entity.getPublishedAt()).isNotNull();
            ArgumentCaptor<SchemaPublishedEvent> captor =
                    ArgumentCaptor.forClass(SchemaPublishedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("重复发布或 blocks 非法拒绝")
        void publishGuards() {
            entity.setStatus(Status.PUBLISHED.getCode());
            entity.setBlocks("[{\"id\":\"b1\",\"blockType\":\"form\"}]");
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            assertThatThrownBy(() -> service.publish("pid-1"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("已经发布");

            entity.setStatus(Status.DRAFT.getCode());
            entity.setBlocks("   ");
            assertThatThrownBy(() -> service.publish("pid-1"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("Blocks format is invalid");
        }

        @Test
        @DisplayName("取消发布：未发布拒绝，已发布回 draft 并清 publishedAt")
        void unpublishGuards() {
            entity.setStatus(Status.DRAFT.getCode());
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            assertThatThrownBy(() -> service.unpublish("pid-1"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("未发布");

            entity.setStatus(Status.PUBLISHED.getCode());
            entity.setPublishedAt(Instant.now());
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(entity));
            service.unpublish("pid-1");
            assertThat(entity.getStatus()).isEqualTo(Status.DRAFT.getCode());
            assertThat(entity.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("createVersion 复制字段并递增版本号")
        void createVersionCopiesAndIncrements() {
            entity.setVersion(4);
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(entity));

            service.createVersion("pid-1", "snapshot");

            ArgumentCaptor<PageSchema> captor = ArgumentCaptor.forClass(PageSchema.class);
            verify(pageSchemaMapper).insert(captor.capture());
            PageSchema newVersion = captor.getValue();
            assertThat(newVersion.getVersion()).isEqualTo(5);
            assertThat(newVersion.getIsCurrent()).isFalse();
            assertThat(newVersion.getStatus()).isEqualTo(Status.DRAFT.getCode());
            assertThat(newVersion.getName()).isEqualTo("home-page");
            assertThat(newVersion.getPid()).isNotEqualTo("pid-1");
        }

        @Test
        @DisplayName("getVersionHistory 按名称查全版本")
        void versionHistoryMapped() {
            when(pageSchemaMapper.selectByPid("pid-1")).thenReturn(entity);
            when(pageSchemaMapper.selectVersionsByName("home-page")).thenReturn(List.of(entity));
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(entity));

            assertThat(service.getVersionHistory("pid-1")).hasSize(1);
        }
    }

    // ==================== 查询 ====================

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        @DisplayName("findByName 空参/缺失拒绝，命中转 DTO")
        void findByNameBranches() {
            assertThatThrownBy(() -> service.findByName(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面名称不能为空");
            when(pageSchemaMapper.selectByName("home-page")).thenReturn(null);
            assertThatThrownBy(() -> service.findByName("home-page"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面配置不存在");
            when(pageSchemaMapper.selectByName("home-page")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(entity)).thenReturn(dto(entity));
            assertThat(service.findByName("home-page").getPid()).isEqualTo("pid-1");
        }

        @Test
        @DisplayName("findByKind 空 kind 拒绝")
        void findByKindRejectsBlank() {
            assertThatThrownBy(() -> service.findByKind(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("kind");
        }

        @Test
        @DisplayName("分页结果转换为轻量列表 DTO")
        void paginationMapped() {
            IPage<PageSchema> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(List.of(entity));
            page.setTotal(11);
            when(pageSchemaMapper.selectPageList(any(), any(), any(), any(), any())).thenReturn(page);
            PageSchemaListDTO listDto = new PageSchemaListDTO();
            when(pageSchemaConverter.toListDTO(entity)).thenReturn(listDto);

            PaginationRequest request = new PaginationRequest();
            request.setPageNum(2);
            request.setPageSize(5);
            PaginationResult<PageSchemaListDTO> result =
                    service.findPageWithConditions("list", null, null, "home", request);

            assertThat(result.getRecords()).containsExactly(listDto);
            assertThat(result.getTotal()).isEqualTo(11);
        }

        @Test
        @DisplayName("findByEntityCode 空参拒绝，命中或未命中")
        void findByEntityCodeBranches() {
            assertThatThrownBy(() -> service.findByEntityCode(" ", "list"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("实体编码");
            when(pageSchemaMapper.selectOne(any(Wrapper.class))).thenReturn(entity);
            when(pageSchemaConverter.toDTO(entity)).thenReturn(dto(entity));
            assertThat(service.findByEntityCode("crm_customer", "list").getPid()).isEqualTo("pid-1");
            when(pageSchemaMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            assertThat(service.findByEntityCode("crm_customer", "list")).isNull();
        }

        @Test
        @DisplayName("findByModelCode 空 modelCode 返回空表")
        void findByModelCodeBlank() {
            assertThat(service.findByModelCode(" ")).isEmpty();
        }

        @Test
        @DisplayName("计数与唯一性委托 mapper")
        void countersDelegate() {
            when(pageSchemaMapper.countTotal()).thenReturn(3L);
            when(pageSchemaMapper.countPublished()).thenReturn(2L);
            when(pageSchemaMapper.countTemplates()).thenReturn(1L);
            when(pageSchemaMapper.countByName("home-page", null)).thenReturn(0L);
            assertThat(service.countTotal()).isEqualTo(3);
            assertThat(service.countPublished()).isEqualTo(2);
            assertThat(service.countTemplates()).isEqualTo(1);
            assertThat(service.isNameUnique("home-page", null)).isTrue();
        }
    }

    // ==================== 运行时组合 ====================

    @Nested
    @DisplayName("运行时组合与系统 Tab")
    class RuntimeComposition {

        @Test
        @DisplayName("findRuntimeByPid 空参拒绝、缺失返回 null、命中走组合链")
        void findRuntimeByPidBranches() {
            assertThatThrownBy(() -> service.findRuntimeByPid(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面PID");
            when(pageSchemaMapper.selectPublishedByPid("ghost")).thenReturn(null);
            assertThat(service.findRuntimeByPid("ghost")).isNull();

            when(pageSchemaMapper.selectPublishedByPid("pid-1")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(entity)).thenReturn(dto(entity));
            PageSchemaDTO runtime = service.findRuntimeByPid("pid-1");
            assertThat(runtime.getPid()).isEqualTo("pid-1");
            verify(authoringRuntimePageMaterializer).materialize(any());
            verify(pageSchemaContributionComposer).compose(any(), anyList());
        }

        @Test
        @DisplayName("findAnyByPageKey 空 key 拒绝；detail 页注入系统 Tab 并去重")
        void systemTabsInjectedForDetailPages() {
            assertThatThrownBy(() -> service.findAnyByPageKey(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面键");

            PageSchemaDTO detail = dto(entity);
            detail.setModelCode("crm_customer");
            Map<String, Object> merchantTabs = new HashMap<>();
            merchantTabs.put("blockType", "tabs");
            merchantTabs.put("tabs", new java.util.ArrayList<>(List.of(
                    Map.of("key", "main", "blocks", List.of(block("f1", "form"))))));
            detail.setBlocks(new java.util.ArrayList<>(List.of(block("t1", "tabs-wrap"), merchantTabs)));
            when(pageSchemaMapper.selectAnyByPageKey("home")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(entity)).thenReturn(detail);
            com.auraboot.framework.meta.entity.Model model = new com.auraboot.framework.meta.entity.Model();
            model.setModelCategory("customer");
            when(metaModelMapper.findCurrentByCode("crm_customer")).thenReturn(model);

            PageSchemaDTO result = service.findAnyByPageKey("home");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) (Object) result.getBlocks();
            Map<String, Object> tabsBlock = blocks.stream()
                    .filter(b -> "tabs".equals(b.get("blockType")))
                    .findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tabs = (List<Map<String, Object>>) tabsBlock.get("tabs");
            // The merchant tab survives and the deduplicated system tab is appended.
            assertThat(tabs.stream().map(t -> t.get("key")))
                    .contains("main", "__field_history__");
        }

        @Test
        @DisplayName("非 detail 页跳过系统 Tab 注入")
        void systemTabsSkippedForNonDetail() {
            PageSchemaDTO list = dto(entity);
            list.setKind("list");
            when(pageSchemaMapper.selectAnyByPageKey("home")).thenReturn(entity);
            when(pageSchemaConverter.toDTO(entity)).thenReturn(list);

            assertThat(service.findAnyByPageKey("home")).isSameAs(list);
        }

        @Test
        @DisplayName("findByPageKey 空参拒绝；缺失返回 null")
        void findByPageKeyGuards() {
            assertThatThrownBy(() -> service.findByPageKey(" "))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("页面键");
            when(pageSchemaMapper.selectByPageKey("ghost")).thenReturn(null);
            assertThat(service.findByPageKey("ghost")).isNull();
        }

        @Test
        @DisplayName("定义主读开启且 release 主导时返回 release 页面")
        void releasePrimaryResolutionWins() {
            ReflectionTestUtils.setField(service, "pageDefinitionPrimaryEnabled", true);
            ReflectionTestUtils.setField(service, "defaultApplicationCode", "app-main");
            MetaContext.setCurrentTenantId(42L);

            // Rebuild the real read service against stubs we control.
            ApplicationDefinitionMapper definitions =
                    org.mockito.Mockito.mock(ApplicationDefinitionMapper.class);
            com.auraboot.framework.application.release.ApplicationDefinitionResolver resolver =
                    org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationDefinitionResolver.class);
            ApplicationPageDefinitionReadService readService = new ApplicationPageDefinitionReadService(
                    definitions, resolver, objectMapper);
            ReflectionTestUtils.setField(service, "applicationPageDefinitionReadService", readService);

            PageSchema baselineEntity = pageSchema();
            when(pageSchemaMapper.selectByPageKey("home")).thenReturn(baselineEntity);
            when(pageSchemaConverter.toDTO(any(PageSchema.class))).thenAnswer(inv -> dto(baselineEntity));

            ApplicationDefinitionMapper.ReleaseRow row = new ApplicationDefinitionMapper.ReleaseRow();
            row.applicationId = 1L;
            row.code = "app-main";
            row.releaseId = "rel-1";
            row.releaseDigest = "digest-1";
            row.sourceLockIdentity = "lock-1";
            row.status = "active";
            row.bindingVersion = 3L;
            when(definitions.findBoundRelease(42L, "app-main")).thenReturn(row);

            var selection = new com.auraboot.framework.application.release.ApplicationDefinitionResolver
                    .ReleaseSelection(1L, "app-main", "rel-1", "digest-1", "lock-1", "active", 3L);
            var definitions0 = new com.auraboot.framework.application.release.ApplicationDefinitionResolver
                    .ReleaseDefinitions(selection, List.of());
            com.fasterxml.jackson.databind.JsonNode pageJson = objectMapper.valueToTree(Map.of(
                    "pageKey", "home",
                    "kind", "list",
                    "name", "Home from release"));
            var resolved = new com.auraboot.framework.application.release.ApplicationDefinitionResolver
                    .ResolvedDefinition(selection, "page", "digest-1",
                    com.auraboot.framework.application.release.ApplicationDefinitionResolver.ResourceType.PAGE,
                    "storefront", "home", null, pageJson);
            var lookup = new com.auraboot.framework.application.release.ApplicationDefinitionResolver
                    .UniqueDefinitionLookup(definitions0, resolved);
            when(resolver.lookupUnique(eq(42L), eq("app-main"), any(), eq("home"), any())).thenReturn(lookup);

            PageSchemaDTO primary = service.findByPageKey("home");

            assertThat(primary).isNotNull();
            assertThat(primary.getPageKey()).isEqualTo("home");
            // The release page composes through contributions + system tabs,
            // skipping the legacy materializer.
            verify(pageSchemaContributionComposer).compose(any(), anyList());
        }
    }

    // ==================== 移动端同步 ====================

    @Nested
    @DisplayName("移动端同步")
    class MobileSync {

        @Test
        @DisplayName("batchGetByKeys 空 key 列表短路")
        void batchGetShortCircuits() {
            assertThat(service.batchGetByKeys(List.of())).isEmpty();
            verify(pageSchemaMapper, never()).selectBatchByKeys(any());
        }

        @Test
        @DisplayName("getVersionsSince 无 release 时装配 PAGE_SCHEMA runtime")
        void versionsSinceWithoutRelease() {
            PageSchemaSyncVersionDTO version = new PageSchemaSyncVersionDTO();
            version.setPagePid("pid-1");
            version.setRuntimeSourceVersion(null);
            when(pageSchemaMapper.selectVersionsSince(any())).thenReturn(List.of(version));

            service.getVersionsSince(Instant.EPOCH);

            PageSchemaRuntimeDTO runtime = version.getRuntime();
            assertThat(runtime.source()).isEqualTo("PAGE_SCHEMA");
            assertThat(runtime.sourceVersion()).isEqualTo(1L);
            assertThat(runtime.cacheKey()).contains("page-schema:pid-1:1");
        }

        @Test
        @DisplayName("getVersionsSince 有 release 时装配 AUTHORING_RELEASE runtime")
        void versionsSinceWithRelease() {
            PageSchemaSyncVersionDTO version = new PageSchemaSyncVersionDTO();
            version.setPagePid("pid-2");
            version.setRuntimeReleasePid("rel-9");
            version.setRuntimeChannelVersion(3L);
            version.setRuntimeSourceVersion(5L);
            version.setRuntimeSnapshotChecksum("sha256:abc");
            when(pageSchemaMapper.selectVersionsSince(any())).thenReturn(List.of(version));

            service.getVersionsSince(Instant.EPOCH);

            PageSchemaRuntimeDTO runtime = version.getRuntime();
            assertThat(runtime.source()).isEqualTo("AUTHORING_RELEASE");
            assertThat(runtime.releasePid()).isEqualTo("rel-9");
            assertThat(runtime.channelVersion()).isEqualTo(3L);
            assertThat(runtime.snapshotChecksum()).isEqualTo("sha256:abc");
        }
    }

    // ==================== validateBlocks ====================

    @Nested
    @DisplayName("blocks 校验")
    class ValidateBlocks {

        @Test
        @DisplayName("null/空表拒绝，字符串按长度判定，其余对象放行")
        void branches() {
            assertThat(service.validateBlocks(null)).isFalse();
            assertThat(service.validateBlocks(List.of())).isFalse();
            assertThat(service.validateBlocks(List.of(block("b1", "form")))).isTrue();
            assertThat(service.validateBlocks("  ")).isFalse();
            assertThat(service.validateBlocks("[]")).isTrue();
            assertThat(service.validateBlocks(new Object())).isTrue();
        }
    }
}
