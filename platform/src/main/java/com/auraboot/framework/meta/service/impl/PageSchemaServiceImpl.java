package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.validator.PageSchemaDslI18nValidator;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.converter.PageSchemaConverter;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.event.SchemaPublishedEvent;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.schema.SystemTabRegistry;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 页面配置服务实现类
 * 提供页面配置管理的核心业务逻辑
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PageSchemaServiceImpl implements PageSchemaService {

    private final PageSchemaMapper pageSchemaMapper;
    private final PageSchemaConverter pageSchemaConverter;
    private final com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissionAssignmentService;
    private final com.auraboot.framework.meta.mapper.MetaModelMapper metaModelMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== CRUD 基础方法 ====================

    @Override
    public PageSchemaDTO create(PageSchemaCreateRequest request) {
        log.info("创建页面配置: {}", request.getName());
        
        // 业务验证
        validateCreateRequest(request);
        validatePageKeyUnique(request.getPageKey(), null);
        validateNameUnique(request.getName(), null);
        
        // 转换实体并设置默认值
        PageSchema pageSchema = pageSchemaConverter.toEntity(request);
        pageSchema.setPid(UniqueIdGenerator.generate());
        pageSchema.setStatus(Status.DRAFT.getCode());
        pageSchema.setExtension(new com.auraboot.framework.meta.entity.payload.ExtensionBean());
        pageSchema.setVersion(1);
        pageSchema.setIsCurrent(true);
        pageSchema.setCreatedAt(Instant.now());
        pageSchema.setUpdatedAt(Instant.now());

        // Set plugin_pid if provided
        if (StringUtils.hasText(request.getPluginPid())) {
            pageSchema.setPluginPid(request.getPluginPid());
        }

        // 保存到数据库
        pageSchemaMapper.insert(pageSchema);
        
        log.info("页面配置创建成功: {}", pageSchema.getPid());
        
        // 自动分配 permissions
        try {
            autoPermissionAssignmentService.autoAssignPermissions(
                pageSchema.getName(), // modelCode (page name as resource code)
                null
            );
            log.info("Auto permission assignment completed for page: {}", pageSchema.getName());
        } catch (Exception e) {
            log.error("Auto permission assignment failed (non-blocking): name={}, error={}",
                pageSchema.getName(), e.getMessage(), e);
            // 不影响页面创建
        }
        
        return pageSchemaConverter.toDTO(pageSchema);
    }

    @Override
    public PageSchemaDTO update(String pid, PageSchemaUpdateRequest request) {
        log.info("更新页面配置: {}", pid);

        // 查找现有记录
        PageSchema existingSchema = findEntityByPid(pid);

        // Optimistic lock: if client provides rowVersion, verify it matches
        if (request.getRowVersion() != null) {
            Integer currentVersion = existingSchema.getRowVersion() != null ? existingSchema.getRowVersion() : 1;
            if (!currentVersion.equals(request.getRowVersion())) {
                throw new com.auraboot.framework.exception.ConflictException(
                    String.format("Version conflict: expected %d, but current is %d. " +
                        "Another user may have modified this page.", request.getRowVersion(), currentVersion));
            }
        }

        // 业务验证
        validateUpdateRequest(request);
        if (StringUtils.hasText(request.getPageKey()) &&
            !request.getPageKey().equals(existingSchema.getPageKey())) {
            validatePageKeyUnique(request.getPageKey(), pid);
        }
        if (StringUtils.hasText(request.getName()) &&
            !request.getName().equals(existingSchema.getName())) {
            validateNameUnique(request.getName(), pid);
        }

        // 更新实体
        pageSchemaConverter.updateEntity(existingSchema, request);
        existingSchema.setUpdatedAt(Instant.now());
        // Increment row_version for optimistic lock
        existingSchema.setRowVersion((existingSchema.getRowVersion() != null ? existingSchema.getRowVersion() : 1) + 1);

        // 保存更新
        pageSchemaMapper.updateById(existingSchema);

        log.info("页面配置更新成功: {}", pid);
        return pageSchemaConverter.toDTO(existingSchema);
    }

    @Override
    public void delete(String pid) {
        log.info("删除页面配置: {}", pid);
        
        PageSchema pageSchema = findEntityByPid(pid);
        
        // 检查是否可以删除
        validateCanDelete(pageSchema);
        
        // 软删除 — use removeById so @TableLogic generates correct SQL
        pageSchemaMapper.deleteById(pageSchema.getId());
        
        log.info("页面配置删除成功: {}", pid);
    }

    @Override
    public PageSchemaDTO findByPid(String pid) {
        PageSchema pageSchema = findEntityByPid(pid);
        return pageSchemaConverter.toDTO(pageSchema);
    }

    // ==================== 业务查询方法 ====================

    @Override
    public PageSchemaDTO findByName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面名称不能为空");
        }
        
        PageSchema pageSchema = pageSchemaMapper.selectByName(name);
        if (pageSchema == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "页面配置不存在: " + name);
        }
        
        return pageSchemaConverter.toDTO(pageSchema);
    }

    @Override
    public List<PageSchemaDTO> findByKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Page kind must not be empty");
        }

        List<PageSchema> schemas = pageSchemaMapper.selectByKind(kind);
        return schemas.stream()
                .map(pageSchemaConverter::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PageSchemaDTO> findTemplateSchemas(String templateCategory) {
        List<PageSchema> schemas = pageSchemaMapper.selectTemplateSchemas(templateCategory);
        return schemas.stream()
                .map(pageSchemaConverter::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PageSchemaDTO> findPublishedSchemas() {
        List<PageSchema> schemas = pageSchemaMapper.selectPublishedSchemas();
        return schemas.stream()
                .map(pageSchemaConverter::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 根据关键词搜索页面配置
     * 支持在页面名称、标题、描述中进行模糊搜索
     * 
     * @param keyword 搜索关键词
     * @return 匹配的页面配置DTO列表
     */
    @Override
    public List<PageSchemaDTO> searchByKeyword(String keyword) {
        List<PageSchema> schemas = pageSchemaMapper.selectByKeyword(keyword);
        return schemas.stream()
                .map(pageSchemaConverter::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PaginationResult<PageSchemaListDTO> findPageWithConditions(
            String kind, Boolean isTemplate, Boolean isPublished, String keyword, PaginationRequest request) {

        Page<PageSchema> pageRequest = new Page<>(request.getPageNum(), request.getPageSize());
        IPage<PageSchema> pageResult = pageSchemaMapper.selectPageList(
            pageRequest, kind, isTemplate, isPublished, keyword
        );
        List<PageSchema> schemas = pageResult.getRecords();
        long total = pageResult.getTotal();

        // Convert to lightweight list DTO (no blocks/layout)
        List<PageSchemaListDTO> dtoList = schemas.stream()
                .map(pageSchemaConverter::toListDTO)
                .collect(Collectors.toList());

        return new PaginationResult<>(
                dtoList,
                total,
                request.getPageNum(),
                request.getPageSize()
        );
    }

    // ==================== 版本管理方法 ====================

    @Override
    public PageSchemaDTO publish(String pid) {
        log.info("发布页面配置: {}", pid);
        
        PageSchema pageSchema = findEntityByPid(pid);
        
        // 验证是否可以发布
        validateCanPublish(pageSchema);
        
        // 更新发布状态
        pageSchema.setStatus(Status.PUBLISHED.getCode());
        pageSchema.setPublishedAt(Instant.now());
        pageSchema.setUpdatedAt(Instant.now());
        
        pageSchemaMapper.updateById(pageSchema);

        // Publish event for mobile sync notifications, cache invalidation, etc.
        int schemaVersion = pageSchema.getSchemaVersion() != null ? pageSchema.getSchemaVersion() : 1;
        eventPublisher.publishEvent(new SchemaPublishedEvent(this, pageSchema.getPageKey(), schemaVersion));

        log.info("页面配置发布成功: {}", pid);
        return pageSchemaConverter.toDTO(pageSchema);
    }

    @Override
    public PageSchemaDTO unpublish(String pid) {
        log.info("取消发布页面配置: {}", pid);
        
        PageSchema pageSchema = findEntityByPid(pid);
        
        // 验证是否可以取消发布
        validateCanUnpublish(pageSchema);
        
        // 更新状态
        pageSchema.setStatus(Status.DRAFT.getCode());
        pageSchema.setPublishedAt(null);
        pageSchema.setUpdatedAt(Instant.now());
        
        pageSchemaMapper.updateById(pageSchema);
        
        log.info("页面配置取消发布成功: {}", pid);
        return pageSchemaConverter.toDTO(pageSchema);
    }

    @Override
    public PageSchemaDTO createVersion(String pid, String reason) {
        log.info("创建页面配置版本: {}, 备注: {}", pid, reason);
        
        PageSchema originalSchema = findEntityByPid(pid);
        
        // 创建新版本
        PageSchema newVersion = createNewVersion(originalSchema, reason);
        
        // 保存新版本
        pageSchemaMapper.insert(newVersion);
        
        log.info("页面配置版本创建成功: {}", newVersion.getPid());
        return pageSchemaConverter.toDTO(newVersion);
    }

    @Override
    public List<PageSchemaDTO> getVersionHistory(String pid) {
        PageSchema pageSchema = findEntityByPid(pid);
        
        List<PageSchema> versions = pageSchemaMapper.selectVersionsByName(pageSchema.getName());
        return versions.stream()
                .map(pageSchemaConverter::toDTO)
                .collect(Collectors.toList());
    }

    // ==================== 统计和验证方法 ====================

    @Override
    public long countTotal() {
        return pageSchemaMapper.countTotal();
    }

    @Override
    public long countPublished() {
        return pageSchemaMapper.countPublished();
    }

    @Override
    public long countTemplates() {
        return pageSchemaMapper.countTemplates();
    }

    @Override
    public boolean isNameUnique(String name, String excludePid) {
        return pageSchemaMapper.countByName(name, excludePid) == 0;
    }

    @Override
    public boolean validateBlocks(Object blocks) {
        if (blocks == null) {
            return false;
        }

        try {
            if (blocks instanceof List) {
                List<?> blockList = (List<?>) blocks;
                if (blockList.isEmpty()) {
                    log.warn("Blocks validation failed: empty list");
                    return false;
                }
                // Size limit: max 512KB serialized
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(blockList);
                if (json.length() > 512 * 1024) {
                    log.warn("Blocks validation failed: exceeds 512KB limit ({}KB)", json.length() / 1024);
                    return false;
                }
                return true;
            } else if (blocks instanceof String) {
                String str = (String) blocks;
                return !str.isBlank() && str.length() <= 512 * 1024;
            }
            return true;
        } catch (Exception e) {
            log.warn("Blocks validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 统一控制器支持方法 ====================

    @Override
    public PageSchemaDTO findByEntityCode(String entityCode, String schemaType) {
        if (!StringUtils.hasText(entityCode) || !StringUtils.hasText(schemaType)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "实体编码和Schema类型不能为空");
        }
        
        PageSchema pageSchema = pageSchemaMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PageSchema>()
                        .eq(PageSchema::getModelCode, entityCode)
                        .eq(PageSchema::getKind, schemaType)
                        .eq(PageSchema::getDeletedFlag, false)
                        .last("LIMIT 1"));
        return pageSchema != null ? pageSchemaConverter.toDTO(pageSchema) : null;
    }

    @Override
    public PageSchemaDTO findByPageKey(String pageKey) {
        if (!StringUtils.hasText(pageKey)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面键不能为空");
        }

        // 直接通过 page_key 查询
        PageSchema pageSchema = pageSchemaMapper.selectByPageKey(pageKey);
        return pageSchema != null ? injectSystemTabs(enrichWithModelCategory(pageSchemaConverter.toDTO(pageSchema))) : null;
    }

    @Override
    public PageSchemaDTO findAnyByPageKey(String pageKey) {
        if (!StringUtils.hasText(pageKey)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面键不能为空");
        }

        PageSchema pageSchema = pageSchemaMapper.selectAnyByPageKey(pageKey);
        return pageSchema != null ? injectSystemTabs(enrichWithModelCategory(pageSchemaConverter.toDTO(pageSchema))) : null;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * Enrich PageSchemaDTO with modelCategory from the associated model.
     */
    private PageSchemaDTO enrichWithModelCategory(PageSchemaDTO dto) {
        if (dto != null && StringUtils.hasText(dto.getModelCode())) {
            try {
                var model = metaModelMapper.findCurrentByCode(dto.getModelCode());
                if (model != null) {
                    dto.setModelCategory(model.getModelCategory());
                }
            } catch (Exception e) {
                log.debug("Failed to enrich modelCategory for {}: {}", dto.getModelCode(), e.getMessage());
            }
        }
        return dto;
    }

    /**
     * Inject system tabs into the blocks list based on modelCategory.
     * Finds a block with blockType "tabs" in the flat blocks list and appends
     * system tabs with deduplication by key.
     */
    @SuppressWarnings("unchecked")
    private PageSchemaDTO injectSystemTabs(PageSchemaDTO dto) {
        if (dto == null || dto.getBlocks() == null || dto.getBlocks().isEmpty()) {
            return dto;
        }
        // Only inject for detail pages
        String kind = dto.getKind();
        if (kind != null && !"detail".equalsIgnoreCase(kind)) {
            return dto;
        }

        // Find the tabs block in the flat blocks list
        List<Map<String, Object>> tabsList = null;
        Map<String, Object> tabsBlock = null;
        for (Object blockObj : dto.getBlocks()) {
            if (!(blockObj instanceof Map)) continue;
            Map<String, Object> block = (Map<String, Object>) blockObj;
            if ("tabs".equals(block.get("blockType")) || block.containsKey("tabs")) {
                Object tabs = block.get("tabs");
                if (tabs instanceof List) {
                    tabsBlock = block;
                    tabsList = (List<Map<String, Object>>) tabs;
                    break;
                }
            }
        }

        if (tabsList == null) {
            return dto;
        }

        // Collect existing tab keys for deduplication
        Set<String> existingKeys = new HashSet<>();
        for (Map<String, Object> tab : tabsList) {
            Object key = tab.get("key");
            if (key != null) {
                existingKeys.add(key.toString());
            }
        }

        // Get system tabs for this model category and append non-duplicates
        String category = dto.getModelCategory();
        List<Map<String, Object>> systemTabs = SystemTabRegistry.getSystemTabs(category);
        List<Map<String, Object>> mutableTabs = new ArrayList<>(tabsList);
        boolean modified = false;
        for (Map<String, Object> sysTab : systemTabs) {
            String key = (String) sysTab.get("key");
            if (!existingKeys.contains(key)) {
                mutableTabs.add(sysTab);
                modified = true;
            }
        }

        if (modified) {
            tabsBlock.put("tabs", mutableTabs);
        }

        return dto;
    }

    /**
     * 根据 PID 查找实体
     */
    private PageSchema findEntityByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID 不能为空");
        }
        
        PageSchema pageSchema = pageSchemaMapper.selectByPid(pid);
        if (pageSchema == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "页面配置不存在: " + pid);
        }
        
        return pageSchema;
    }

    /**
     * 验证创建请求
     */
    private void validateCreateRequest(PageSchemaCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }

        if (!StringUtils.hasText(request.getPageKey())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面Key不能为空");
        }

        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面名称不能为空");
        }

        if (!StringUtils.hasText(request.getKind())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Page kind must not be empty");
        }

        // Blocks may be empty at creation — user builds the page incrementally in the designer.
        // Validation of publishable state happens at publish time, not at create time.
        if (request.getBlocks() != null && !request.getBlocks().isEmpty() && !validateBlocks(request.getBlocks())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Blocks format is invalid");
        }

        // I18n compliance: scan page-level fields AND all blocks recursively
        Map<String, Object> pageMap = new HashMap<>();
        pageMap.put("title", request.getTitle());
        pageMap.put("description", request.getDescription());
        pageMap.put("blocks", request.getBlocks());
        PageSchemaDslI18nValidator.validatePageSchema(pageMap, request.getPageKey());
    }

    /**
     * 验证更新请求
     */
    private void validateUpdateRequest(PageSchemaUpdateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "更新请求不能为空");
        }

        if (request.getBlocks() != null && !request.getBlocks().isEmpty() &&
            !validateBlocks(request.getBlocks())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Blocks format is invalid");
        }

        // I18n compliance: scan page-level fields AND all blocks recursively
        String pageKey = request.getPageKey() != null ? request.getPageKey() : "(update)";
        Map<String, Object> pageMap = new HashMap<>();
        pageMap.put("title", request.getTitle());
        pageMap.put("description", request.getDescription());
        pageMap.put("blocks", request.getBlocks());
        PageSchemaDslI18nValidator.validatePageSchema(pageMap, pageKey);
    }

    /**
     * 验证名称唯一性
     */
    private void validateNameUnique(String name, String excludePid) {
        if (!isNameUnique(name, excludePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "页面名称已存在: " + name);
        }
    }

    /**
     * 验证 pageKey 唯一性
     */
    private void validatePageKeyUnique(String pageKey, String excludePid) {
        if (!isPageKeyUnique(pageKey, excludePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "页面Key已存在: " + pageKey);
        }
    }

    /**
     * 检查 pageKey 是否唯一
     */
    private boolean isPageKeyUnique(String pageKey, String excludePid) {
        return pageSchemaMapper.countByPageKey(pageKey, excludePid) == 0;
    }

    /**
     * 验证是否可以删除
     */
    private void validateCanDelete(PageSchema pageSchema) {
        if (Status.PUBLISHED.getCode().equals(pageSchema.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "已发布的页面配置不能删除，请先取消发布");
        }
    }

    /**
     * 验证是否可以发布
     */
    private void validateCanPublish(PageSchema pageSchema) {
        if (Status.PUBLISHED.getCode().equals(pageSchema.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "页面配置已经发布");
        }
        
        if (!validateBlocks(pageSchema.getBlocks())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "Blocks format is invalid, cannot publish");
        }
    }

    /**
     * 验证是否可以取消发布
     */
    private void validateCanUnpublish(PageSchema pageSchema) {
        if (!Status.PUBLISHED.getCode().equals(pageSchema.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "页面配置未发布，无法取消发布");
        }
    }

    /**
     * 创建新版本
     */
    private PageSchema createNewVersion(PageSchema original, String versionNote) {
        PageSchema newVersion = new PageSchema();
        
        // 复制基本信息
        newVersion.setPid(UniqueIdGenerator.generate());
        newVersion.setName(original.getName());
        newVersion.setTitle(original.getTitle());
        newVersion.setDescription(original.getDescription());
        newVersion.setKind(original.getKind());
        newVersion.setProfile(original.getProfile());
        newVersion.setLayout(original.getLayout());
        newVersion.setBlocks(original.getBlocks());
        newVersion.setMetaInfo(original.getMetaInfo());
        newVersion.setIsTemplate(original.getIsTemplate());
        newVersion.setTemplateCategory(original.getTemplateCategory());
        newVersion.setSortWeight(original.getSortWeight());
        newVersion.setTags(original.getTags());
        
        // 设置版本信息
        newVersion.setVersion(original.getVersion() + 1);
        newVersion.setIsCurrent(false);
        newVersion.setStatus(Status.DRAFT.getCode());
        
        // 设置审计信息
        newVersion.setCreatedAt(Instant.now());
        newVersion.setUpdatedAt(Instant.now());
        newVersion.setTenantId(original.getTenantId());

        return newVersion;
    }

    // ==================== 模型关联查询 ====================

    @Override
    public List<PageSchemaDTO> findByModelCode(String modelCode) {
        log.info("根据模型编码查询关联页面: modelCode={}", modelCode);

        if (!StringUtils.hasText(modelCode)) {
            return List.of();
        }

        List<PageSchema> pages = pageSchemaMapper.selectByModelCode(modelCode);
        return pageSchemaConverter.toDTOList(pages);
    }

    // ==================== Mobile Sync Support ====================

    @Override
    @Transactional(readOnly = true)
    public List<PageSchemaSyncVersionDTO> getVersionsSince(Instant since) {
        log.info("Query schema versions since: {}", since);
        return pageSchemaMapper.selectVersionsSince(since);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageSchemaDTO> batchGetByKeys(List<String> pageKeys) {
        log.info("Batch get schemas by keys, count={}", pageKeys.size());
        if (pageKeys.isEmpty()) {
            return List.of();
        }
        List<PageSchema> schemas = pageSchemaMapper.selectBatchByKeys(pageKeys);
        return pageSchemaConverter.toDTOList(schemas);
    }
}
