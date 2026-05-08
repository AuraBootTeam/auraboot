package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.common.util.DateUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 字段元数据服务实现类
 * 
 * 已整合Git-First架构支持
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaFieldServiceImpl implements MetaFieldService {

    private final MetaFieldMapper metaFieldMapper;
    private final DictService dictService;

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final com.auraboot.framework.meta.converter.ExtensionConverter extensionConverter;
    private final com.auraboot.framework.meta.validator.MetaFieldValidator fieldValidator;
    private final com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper fieldDictBindingMapper;
    private final com.auraboot.framework.meta.service.ModelFieldBindingService modelFieldBindingService;  // ✅ 新增: 模型-字段绑定服务

    @Autowired
    @Lazy
    private com.auraboot.framework.meta.service.MetaModelService metaModelService;

    @Autowired
    @Lazy
    private com.auraboot.framework.meta.service.SchemaManagementService schemaManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;






    // ==================== 基础CRUD操作 ====================

    @Override
    public MetaFieldDTO create(MetaFieldCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }
        
        log.info("创建字段定义: code={}, dataType={}", request.getCode(), request.getDataType());

       return createDirectly(request);
    }

    
    /**
     * 直接创建字段(开发阶段保留)
     */
    private MetaFieldDTO createDirectly(MetaFieldCreateRequest request) {
        log.info("直接创建字段(非Git-First): {}", request.getCode());

        // 业务验证
        validateCreateRequest(request);

        // 获取下一个版本号
        Integer nextVersion = getNextVersion(request.getCode());

        // 只有新建字段（version 1）才验证代码唯一性
        if (nextVersion == 1) {
            validateCodeUnique(request.getCode(), null);
        }

        // 创建字段实体
        Field entity = new Field();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setCode(request.getCode());
        entity.setDataType(request.getDataType());
        entity.setDataSourceId(request.getDataSourceId());
        entity.setVersion(nextVersion);
        entity.setIsCurrent(true);
        entity.setStatus(Boolean.TRUE.equals(request.getAutoPublish()) ? "published"
                : StringUtils.hasText(request.getStatus()) ? request.getStatus() : "draft");
        entity.setTenantId(MetaContext.getCurrentTenantId());

        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        // 设置扩展属性
        if (request.getExtension() != null) {
            // ✅ 使用ExtensionConverter将Map转换为ExtensionBean
            entity.setExtension(extensionConverter.toBean(request.getExtension()));
        }

        // 设置插件PID（如果提供）
        if (StringUtils.hasText(request.getPluginPid())) {
            entity.setPluginPid(request.getPluginPid());
        }

        // 如果不是第一个版本，需要将之前的版本设置为非当前版本
        if (nextVersion > 1) {
            metaFieldMapper.clearCurrentFlag(       request.getCode());
        }

        // 保存实体
        metaFieldMapper.insert(entity);

        log.info("字段定义创建成功: pid={}, code={}, version={} (租户: {})",
                entity.getPid(), entity.getCode(), entity.getVersion(), MetaContext.getCurrentTenantId());

        // Auto-bind to model if modelPid is provided
        if (StringUtils.hasText(request.getModelPid())) {
            bindFieldToModelAfterCreation(request.getModelPid(), request.getCode());
        }

        return convertToDTO(entity);
    }

    @Override
    @Cacheable(value = "metaField", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':pid:' + #pid", unless = "#result == null")
    public MetaFieldDTO findByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            return null;
        }

        log.debug("根据PID查找字段: pid={}", pid);

        // Query directly without throwing exception if not found
        Field entity = metaFieldMapper.selectByPidWithContext(
            pid, MetaContext.getCurrentTenantId()
        );
        
        return entity != null ? convertToDTO(entity) : null;
    }

    @Override
    @Cacheable(value = "metaField", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':id:' + #id")
    public MetaFieldDTO findById(Long id) {
        if (id == null) {
            return null;
        }

        log.debug("根据ID查找字段: id={}", id);

        // 验证租户上下文
          

        // 查询字段实体（MyBatis拦截器会自动注入tenant_id）
        Field entity = metaFieldMapper.selectById(id);
        
        if (entity == null) {
            log.warn("字段不存在: id={}", id);
            return null;
        }

        return convertToDTO(entity);
    }

    @Override
    @Transactional
    @CacheEvict(value = "metaField", allEntries = true)
    public MetaFieldDTO update(String pid, MetaFieldUpdateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "更新请求不能为空");
        }

        log.info("更新字段定义: pid={}", pid);

        // 验证租户上下文
          

        // 查找现有字段（带租户上下文验证）
        Field existingEntity = findEntityByPid(pid);

        // 业务验证
        validateUpdateRequest(request);

        return updateDirectly(existingEntity, request);

    }

    private MetaFieldDTO updateDirectly(Field existingEntity, MetaFieldUpdateRequest request) {
        log.info("直接更新字段(非Git-First): {}", existingEntity.getCode());
        
        // 创建新版本的创建请求
        MetaFieldCreateRequest createRequest = new MetaFieldCreateRequest();
        createRequest.setCode(existingEntity.getCode());
        createRequest.setDataType(request.getDataType());
        createRequest.setDataSourceId(request.getDataSourceId());

        createRequest.setStatus(request.getStatus());
        createRequest.setFeature(request.getFeature());
        createRequest.setRefTarget(request.getRefTarget());
        createRequest.setIndexHint(request.getIndexHint());
        createRequest.setUiSchema(request.getUiSchema());
        createRequest.setQuerySchema(request.getQuerySchema());
        createRequest.setRuleSchema(request.getRuleSchema());
        createRequest.setExtension(request.getExtension());
        createRequest.setVersionNote(request.getVersionNote());
        
        // 创建新版本
        return createDirectly(createRequest);
    }

    @Override
    @Transactional
    @CacheEvict(value = "metaField", allEntries = true)
    public void delete(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID不能为空");
        }
        
        log.info("删除字段定义: pid={}", pid);

        // 验证租户上下文
          

        // 查找字段（带租户上下文验证）
        Field entity = findEntityByPid(pid);

        // 检查是否可以删除
        validateCanDelete(entity);

        deleteDirectly(entity);

    }


    
    /**
     * 直接删除字段(开发阶段保留)
     */
    private void deleteDirectly(Field entity) {
        log.info("直接删除字段(非Git-First): {}", entity.getCode());
        
        // 软删除
        metaFieldMapper.deleteById(entity.getId());
        
        log.info("字段定义删除成功: pid={}, code={}", entity.getPid(), entity.getCode());
    }

    @Override
    public PageResult<MetaFieldDTO> listFields(Integer page, Integer size, String code, 
                                              String dataType, String status,   Boolean currentOnly) {
        log.debug("分页查询字段列表: page={}, size={}, code={}, dataType={}, status={}", 
                 page, size, code, dataType, status);
        
        Page<Field> pageRequest = new Page<>(page, size);
        IPage<Field> pageResult = metaFieldMapper.selectPageList(
            pageRequest, code, dataType, status, currentOnly
        );
        List<Field> entities = pageResult.getRecords();
        long total = pageResult.getTotal();
        
        // 转换为DTO
        List<MetaFieldDTO> dtoList = entities.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        
        return new PageResult<>(
            dtoList,
            total,
            Long.valueOf(size),
            Long.valueOf(page)
        );
    }

    // ==================== 字段查询 ====================

    @Override
    public Optional<MetaFieldDTO> findCurrentByCode(String code) {
        log.debug("根据字段键查询当前版本字段: code={}", code);

        Field entity = metaFieldMapper.findCurrentByCode(code);
        return Optional.ofNullable(entity).map(this::convertToDTO);
    }

    @Override 
    public Optional<MetaFieldDTO> findByCodeAndVersion(String code, Integer version) {
        log.debug("根据字段键和版本查询字段: code={}, version={}", code, version);

              
              

        Field entity = metaFieldMapper.findByCodeAndVersion(  code, version);
        return Optional.ofNullable(entity).map(this::convertToDTO);
    }

    @Override
    public List<MetaFieldDTO> findCurrentByTenant() {
        log.debug("查询当前租户的所有当前版本字段");

              
              

        List<Field> entities = metaFieldMapper.findCurrentByTenant( );
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<MetaFieldDTO> findAllVersionsByCode(String code) {
        log.debug("查询字段的所有版本: code={}", code);

              
              

        List<Field> entities = metaFieldMapper.findAllVersionsByCode(  code);
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<MetaFieldDTO> findByDataType(String dataType) {
        log.debug("根据数据类型查询字段: dataType={}", dataType);

              
              

        List<Field> entities = metaFieldMapper.findByDataType(  dataType);
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<MetaFieldDTO> findByDataSource(Long dataSourceId) {
        log.debug("根据数据源查询字段: dataSourceId={}", dataSourceId);
        
        List<Field> entities = metaFieldMapper.findByDataSource(dataSourceId);
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<MetaFieldDTO> findByStatus(String status) {
        log.debug("根据状态查询字段: status={}", status);

              
              

        List<Field> entities = metaFieldMapper.findByStatus(  status);
        return entities.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    // ==================== 字段验证 ====================

    @Override
    public boolean isCodeUnique(String code, String excludePid) {
        log.debug("检查字段键唯一性: code={}, excludePid={}", code, excludePid);

        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        Long excludeId = null;
        
        if (StringUtils.hasText(excludePid)) {
            Field excludeEntity = metaFieldMapper.findByPid(excludePid);
            if (excludeEntity != null) {
                excludeId = excludeEntity.getId();
            }
        }
        
        int count = metaFieldMapper.countByCode(tenantId,   code, excludeId);
        return count == 0;
    }

    @Override
    public boolean isFieldExists(String code) {
        log.debug("检查字段是否存在: code={}", code);
        
        return findCurrentByCode(code).isPresent();
    }

    @Override
    public MetaFieldValidationResult validateField(String code) {
        log.debug("验证字段定义: code={}", code);
        
        // ✅ 实现字段验证逻辑
        MetaFieldValidationResult result = MetaFieldValidationResult.builder()
            .code(code)
            .build();
        
        try {
            // 查找字段
            Optional<MetaFieldDTO> fieldOpt = findCurrentByCode(code);
            
            if (!fieldOpt.isPresent()) {
                result.addError("code", "not_found", 
                    String.format("Field not found: %s", code));
                return result;
            }
            
            MetaFieldDTO field = fieldOpt.get();
            
            // 验证字段状态
            if ("deleted".equals(field.getStatus())) {
                result.addError("status", "deleted", "Field has been deleted");
            }
            
            // 验证数据类型
            if (!StringUtils.hasText(field.getDataType())) {
                result.addError("dataType", "missing", "Data type is missing");
            }
            
            // 如果是ENUM类型,验证字典绑定
            if ("enum".equals(field.getDataType()) && field.getDataSourceId() == null) {
                result.addWarning("dataSourceId", 
                    "ENUM type field should have a dictionary binding");
            }
            
            // 如果是REFERENCE类型,验证引用目标
            if ("reference".equals(field.getDataType())) {
                // TODO: Validate reference target exists
                result.addWarning("refTarget", 
                    "Reference target validation not implemented");
            }
            
        } catch (Exception e) {
            log.error("Failed to validate field: code={}, error={}", code, e.getMessage(), e);
            result.addError("validation", "error", 
                "Validation failed: " + e.getMessage());
        }
        
        return result;
    }

    // ==================== 字典绑定 ====================

    @Override
    @Transactional
    public boolean bindDictionary(String fieldPid, String dictCode) {
        log.info("绑定字典到字段: fieldPid={}, dictCode={}", fieldPid, dictCode);
        
        // ✅ 实现字典绑定逻辑
        
        // 1. 验证租户上下文
          
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        // 2. 查找字段
        MetaFieldDTO field = findByPid(fieldPid);
        if (field == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "字段不存在: " + fieldPid);
        }
        
        // 3. 验证字段类型 - 支持多种可绑定字典的类型
        List<String> allowedTypes = Arrays.asList("enum", "string", "integer", "array");
        if (!allowedTypes.contains(field.getDataType())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                String.format("字段类型 %s 不支持绑定字典，支持的类型: %s", 
                    field.getDataType(), String.join(", ", allowedTypes)));
        }
        
        // 4. 查找字典
        DictDTO dict = dictService.findByCode(dictCode);
        if (dict == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "字典不存在: " + dictCode);
        }
        
        // 5. 检查是否已存在绑定
        FieldDictBinding existingBinding =
            fieldDictBindingMapper.findByFieldPid(fieldPid, tenantId);
        
        if (existingBinding != null) {
            // 更新现有绑定
            existingBinding.setDictId(dict.getId());
            existingBinding.setDictCode(dictCode);
            existingBinding.setUpdatedAt(Instant.now());
            fieldDictBindingMapper.updateById(existingBinding);
            
            log.info("更新字典绑定: fieldPid={}, dictCode={}", fieldPid, dictCode);
        } else {
            // 创建新绑定
            FieldDictBinding binding =
                FieldDictBinding.builder()
                    .pid(UniqueIdGenerator.generate())
                    .fieldId(field.getId())
                    .fieldPid(fieldPid)
                    .fieldCode(field.getCode())
                    .dictId(dict.getId())
                    .dictCode(dictCode)
                    .tenantId(tenantId)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            
            fieldDictBindingMapper.insert(binding);
            
            log.info("创建字典绑定: fieldPid={}, dictCode={}", fieldPid, dictCode);
        }
        
        return true;
    }

    @Override
    @Transactional
    public boolean unbindDictionary(String fieldPid) {
        log.info("解绑字段的字典: fieldPid={}", fieldPid);
        
        // ✅ 实现字典解绑逻辑
        
        // 1. 验证租户上下文
          
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        // 2. 查找绑定
        FieldDictBinding binding =
            fieldDictBindingMapper.findByFieldPid(fieldPid, tenantId);

        if (binding == null) {
            log.warn("字段没有绑定字典: fieldPid={}", fieldPid);
            return false;
        }

        // 3. 软删除绑定（按字段维度删除，避免残留记录）
        int deletedCount = fieldDictBindingMapper.deleteByFieldPid(fieldPid, tenantId);
        if (deletedCount <= 0) {
            log.warn("字典解绑未生效: fieldPid={}", fieldPid);
            return false;
        }

        log.info("解绑字典成功: fieldPid={}, dictCode={}", fieldPid, binding.getDictCode());
        return true;
    }

    @Override
    public Optional<DictDTO> getBoundDictionary(String fieldPid) {
        log.debug("获取字段绑定的字典: fieldPid={}", fieldPid);
        
        // ✅ 实现获取绑定字典逻辑
        
        // 1. 验证租户上下文
          
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        // 2. 查找绑定
        FieldDictBinding binding =
            fieldDictBindingMapper.findByFieldPid(fieldPid, tenantId  );
        
        if (binding == null) {
            return Optional.empty();
        }
        
        // 3. 查找字典
        DictDTO dict = dictService.findByCode(binding.getDictCode());
        
        return Optional.ofNullable(dict);
    }

    // ==================== 版本管理 ====================

    @Override
    @Transactional
    public MetaFieldDTO publishVersion(String pid) {
        log.info("发布字段版本: pid={}", pid);
        
        Field entity = metaFieldMapper.findByPid(pid);
        if (entity == null) {
            throw new IllegalArgumentException("字段不存在: " + pid);
        }
        
        // 更新状态为已发布
        entity.setStatus(StatusConstants.PUBLISHED);
        entity.setUpdatedAt(Instant.now());
        
        metaFieldMapper.updateById(entity);
        
        return convertToDTO(entity);
    }

    @Override
    @Transactional
    public MetaFieldDTO rollbackToVersion(String code, Integer version) {
        log.info("回滚字段到指定版本: code={}, version={}", code, version);

              
              

        // 查找目标版本
        Field targetEntity = metaFieldMapper.findByCodeAndVersion(  code, version);
        if (targetEntity == null) {
            throw new IllegalArgumentException("目标版本不存在: " + code + " v" + version);
        }
        
        // 清除当前版本标记
        metaFieldMapper.clearCurrentFlag(  code);
        
        // 设置目标版本为当前版本
        metaFieldMapper.setCurrentVersion(targetEntity.getId());
        
        // 刷新缓存
        refreshFieldCache(code);
        
        return convertToDTO(targetEntity);
    }

    @Override
    public Integer getNextVersion(String code) {
        log.debug("获取字段的下一个版本号: code={}", code);

              
              

        return metaFieldMapper.getNextVersion(  code);
    }

    // ==================== 缓存管理 ====================

    @Override
    @CacheEvict(value = {"metaField", "metaFieldByKey"}, key = "#code + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()")
    public void refreshFieldCache(String code) {
        log.info("刷新字段缓存: code={}", code);
    }

    @Override
    @CacheEvict(value = {"metaField", "metaFieldByKey"}, allEntries = true)
    public void clearAllFieldCache() {
        log.info("清除所有字段缓存");
    }

    // ==================== 私有方法 ====================

    /**
     * 将实体转换为DTO
     */
    private MetaFieldDTO convertToDTO(Field entity) {
        if (entity == null) {
            return null;
        }
        
        return MetaFieldDTO.builder()
                .id(entity.getId())
                .pid(entity.getPid())
                .code(entity.getCode())
                .dataType(entity.getDataType())
                .dataSourceId(entity.getDataSourceId())
                .version(entity.getVersion())
                .isCurrent(entity.getIsCurrent())
                .status(entity.getStatus())
                .tenantId(entity.getTenantId())

                .extension(extensionConverter.toMap(entity.getExtension())) // ✅ 使用ExtensionConverter将ExtensionBean转换为Map
                .createdAt(DateUtil.toUtcLocalDateTime(entity.getCreatedAt()))
                .updatedAt(DateUtil.toUtcLocalDateTime(entity.getUpdatedAt()))
                .build();
    }
    
    // ==================== Git-First辅助方法 ====================
    
    /**
     * 根据 PID 查找实体（带租户上下文验证）
     */
    private Field findEntityByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID 不能为空");
        }

        Field entity = metaFieldMapper.selectByPidWithContext(
            pid, MetaContext.getCurrentTenantId()
        );

        if (entity == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "字段不存在或不属于当前租户: " + pid);
        }

        return entity;
    }
    
    /**
     * 根据字段键查找字段（从投影表）
     */
    private MetaFieldDTO findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字段键不能为空");
        }
        
        Optional<MetaFieldDTO> result = findCurrentByCode(code);
        if (!result.isPresent()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "字段不存在: " + code);
        }
        
        return result.get();
    }
    
    /**
     * 构建DSL文件路径
     *
     * @param code 字段键
     * @return DSL文件路径
     */
    private String buildDslPath(String code) {
        return String.format("tenant-%d/dsl/fields/%s.json",
            MetaContext.getCurrentTenantId(), code);
    }

    // ==================== 验证方法 ====================
    
    /**
     * 验证创建请求
     */
    private void validateCreateRequest(MetaFieldCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }
        
        // ✅ 使用MetaFieldValidator进行完整验证
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);
        
        if (!validationResult.isValid()) {
            // 收集所有错误信息
            String errorMessages = validationResult.getErrors().stream()
                .map(error -> String.format("%s: %s", error.getField(), error.getMessage()))
                .collect(Collectors.joining("; "));
            
            log.warn("Field validation failed: code={}, errors={}", request.getCode(), errorMessages);
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "字段验证失败: " + errorMessages);
        }
        
        // 记录警告信息
        if (validationResult.hasWarnings()) {
            String warningMessages = validationResult.getWarnings().stream()
                .map(warning -> String.format("%s: %s", warning.getField(), warning.getMessage()))
                .collect(Collectors.joining("; "));
            log.warn("Field validation warnings: code={}, warnings={}", request.getCode(), warningMessages);
        }
    }
    
    /**
     * 验证更新请求
     */
    private void validateUpdateRequest(MetaFieldUpdateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "更新请求不能为空");
        }

        MetaFieldValidationResult validationResult = fieldValidator.validateUpdateRequest(null, request);

        if (!validationResult.isValid()) {
            String errorMessages = validationResult.getErrors().stream()
                .map(error -> String.format("%s: %s", error.getField(), error.getMessage()))
                .collect(Collectors.joining("; "));

            log.warn("Field update validation failed: errors={}", errorMessages);
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "Field update validation failed: " + errorMessages);
        }

        if (validationResult.hasWarnings()) {
            String warningMessages = validationResult.getWarnings().stream()
                .map(warning -> String.format("%s: %s", warning.getField(), warning.getMessage()))
                .collect(Collectors.joining("; "));
            log.warn("Field update validation warnings: {}", warningMessages);
        }
    }

    /**
     * 验证字段键唯一性
     * For versioning system: only check if CURRENT version with this code exists
     */
    private void validateCodeUnique(String code, String excludePid) {
        // Only check if CURRENT version with this code exists
        Optional<MetaFieldDTO> currentField = findCurrentByCode(code);

        if (currentField.isEmpty()) {
            return;
        }

        if (StringUtils.hasText(excludePid)) {
            Field excludeEntity = findEntityByPid(excludePid);
            if (excludeEntity != null) {
                // Compare by ID to see if it's the same record
                Field currentEntity = metaFieldMapper.findCurrentByCode(

                    code
                );
                if (currentEntity != null && currentEntity.getId().equals(excludeEntity.getId())) {
                    return;
                }
            }
        }

        throw new ValidationException(ResponseCode.CommonValidationFailed,
            "字段键已存在: " + code);
    }
    
    /**
     * 验证是否可以删除
     */
    private void validateCanDelete(Field entity) {
        if (StatusConstants.PUBLISHED.equals(entity.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "已发布的字段不能删除，请先取消发布");
        }
    }
    
    /**
     * Bind field to model after field creation
     * This method is called after field is successfully created via Git-First flow
     * 
     * NOTE: This method has been removed to break circular dependency.
     * Field binding is now handled by ModelFieldBindingService.
     * Bind field to model after creation.
     * 
     * @param modelPid Model PID
     * @param fieldCode Field code
     */
    private void bindFieldToModelAfterCreation(String modelPid, String fieldCode) {
        log.info("Binding field to model after creation: modelPid={}, fieldCode={}", modelPid, fieldCode);
        
        // 查找刚创建的字段 - 使用   code 和最新版本
              
              
        
        // 获取最新版本号 (getNextVersion 返回下一个版本，所以减1得到当前最新版本)
        Integer nextVersion = metaFieldMapper.getNextVersion(  fieldCode);
        if (nextVersion == null || nextVersion <= 1) {
            log.warn("Field not found for binding: code={}", fieldCode);
            return;
        }
        Integer latestVersion = nextVersion - 1;
        
        Field field = metaFieldMapper.findByCodeAndVersion(  fieldCode, latestVersion);
        if (field == null) {
            log.warn("Field not found for binding: code={}, version={}", fieldCode, latestVersion);
            return;
        }
        
        // 调用绑定服务
        try {
            modelFieldBindingService.bindFieldToModel(
                modelPid,
                field.getPid(),
                null,  // displayOrder - 自动计算
                false, // isRequired
                false, // isReadonly
                true   // isVisible
            );
            log.info("Field bound to model successfully: modelPid={}, fieldPid={}", modelPid, field.getPid());
        } catch (Exception e) {
            log.warn("Failed to bind field to model: modelPid={}, fieldPid={}, error={}",
                modelPid, field.getPid(), e.getMessage());
            // 不抛出异常，字段创建成功但绑定失败
        }
    }

    // ==================== AuraBot Skill SPI: addToModel ====================

    /**
     * Whitelist of abstract data types accepted by {@link #addToModel}. Maps
     * onto the dialect's switch arms; {@code int} is normalized to
     * {@code integer} before {@link MetaFieldCreateRequest} construction.
     * See spec §3.4 dataType invariants.
     */
    private static final Set<String> ACCEPTED_DATA_TYPES = Set.of(
            "string", "text", "int", "long", "decimal",
            "boolean", "date", "datetime", "json");

    @Override
    @Transactional
    public AddFieldResult addToModel(AddFieldRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "addToModel request must not be null");
        }
        if (!StringUtils.hasText(request.getModelCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "modelCode must not be blank");
        }
        if (!StringUtils.hasText(request.getCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "code must not be blank");
        }
        if (!StringUtils.hasText(request.getDataType())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "dataType must not be blank");
        }

        String dataTypeLower = request.getDataType().toLowerCase(Locale.ROOT);
        if (!ACCEPTED_DATA_TYPES.contains(dataTypeLower)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "dataType not in whitelist (string|text|int|long|decimal|boolean|date|datetime|json): "
                            + request.getDataType());
        }
        // Map abstract `int` -> dialect's `integer`. Other abstract types pass through.
        String storedDataType = "int".equals(dataTypeLower) ? "integer" : dataTypeLower;

        String modelCode = request.getModelCode();
        String fieldCode = request.getCode();

        // 1) Resolve model. findByCode throws ValidationException when missing — wrap.
        MetaModelDTO model;
        try {
            model = metaModelService.findByCode(modelCode);
        } catch (ValidationException ve) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "modelCode not found: " + modelCode);
        }
        if (model == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "modelCode not found: " + modelCode);
        }

        // 2) Reject duplicate field on this model.
        try {
            List<MetaFieldDTO> bound = modelFieldBindingService.getModelFields(model.getPid());
            boolean dup = bound != null && bound.stream()
                    .anyMatch(f -> f != null && fieldCode.equalsIgnoreCase(f.getCode()));
            if (dup) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "field code already bound to model: model=" + modelCode + ", code=" + fieldCode);
            }
        } catch (ValidationException ve) {
            throw ve;
        } catch (RuntimeException re) {
            // P3 boundary: surface as ValidationException so callers see a stable contract.
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "failed to inspect existing fields for model " + modelCode + ": " + re.getMessage());
        }

        // 3) Build field create request with autoPublish + modelPid for one-shot create+bind.
        MetaFieldCreateRequest fcr = new MetaFieldCreateRequest();
        fcr.setCode(fieldCode);
        fcr.setDataType(storedDataType);
        fcr.setAutoPublish(Boolean.TRUE);
        fcr.setModelPid(model.getPid());

        Map<String, Object> extension = new LinkedHashMap<>();
        if (StringUtils.hasText(request.getDisplayName())) {
            extension.put("displayName", request.getDisplayName());
        }
        if (StringUtils.hasText(request.getComment())) {
            extension.put("description", request.getComment());
        }
        if (request.getMaxLength() != null) {
            extension.put("maxLength", request.getMaxLength());
        }
        if (Boolean.TRUE.equals(request.getRequired())) {
            extension.put("required", Boolean.TRUE);
        }
        if (!extension.isEmpty()) {
            fcr.setExtension(extension);
        }

        MetaFieldDTO created;
        try {
            created = create(fcr);
        } catch (ValidationException ve) {
            throw ve;
        } catch (RuntimeException re) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "field creation failed for " + fieldCode + ": " + re.getMessage());
        }
        if (created == null || !StringUtils.hasText(created.getPid())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "field creation returned no pid for " + fieldCode);
        }

        // 4) Trigger DDL ADD COLUMN. The platform's binding helper only fires
        //    addFieldToModel when model.isPublished() AND when called via
        //    MetaModelService.bindFieldToModel(modelId,fieldId,...). The
        //    auto-bind path goes through ModelFieldBindingService instead (no
        //    DDL trigger), so addToModel must invoke schema management itself.
        SchemaOperationResult schemaResult;
        try {
            schemaResult = schemaManagementService.addFieldToModel(modelCode, fieldCode);
        } catch (RuntimeException re) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "DDL ADD COLUMN failed for " + modelCode + "." + fieldCode + ": " + re.getMessage());
        }
        if (schemaResult == null || !schemaResult.isSuccess()) {
            String err = schemaResult != null ? schemaResult.getErrorMessage() : "null result";
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "DDL ADD COLUMN reported failure for " + modelCode + "." + fieldCode + ": " + err);
        }

        String tableName = StringUtils.hasText(schemaResult.getTableName())
                ? schemaResult.getTableName()
                : metaModelService.getTableName(modelCode);
        String columnName = resolveColumnName(tableName, fieldCode);
        if (columnName == null) {
            // T1 finding: auto-bind silent-log mode means we cannot trust the
            // create() return alone — verify the column actually landed.
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "auto-bind verification failed: column not found in information_schema "
                            + "(table=" + tableName + ", expected code=" + fieldCode
                            + "); check tenant context & model state");
        }
        String pgColumnType = resolvePgColumnType(tableName, columnName);

        return AddFieldResult.builder()
                .fieldPid(created.getPid())
                .storageCode(modelCode + "_" + fieldCode)
                .columnName(columnName)
                .tableName(tableName)
                .pgColumnType(pgColumnType)
                .addedAt(Instant.now())
                .build();
    }

    /**
     * Find the actual column name in {@code information_schema.columns} for the
     * given field code. The platform's {@code generateColumnName} lowercases +
     * camel→snake; we try both forms to be tolerant.
     */
    private String resolveColumnName(String tableName, String fieldCode) {
        String snakeLower = fieldCode
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
        List<String> candidates = snakeLower.equals(fieldCode)
                ? List.of(fieldCode)
                : List.of(snakeLower, fieldCode);
        for (String candidate : candidates) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    Integer.class,
                    tableName, candidate);
            if (count != null && count > 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Read the actual PG type string for a column from
     * {@code information_schema.columns}. Returns a human-readable shape like
     * {@code "varchar(255)"}, {@code "integer"}, {@code "timestamptz"}.
     */
    private String resolvePgColumnType(String tableName, String columnName) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT udt_name, character_maximum_length, numeric_precision, numeric_scale "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                tableName, columnName);
        String udt = String.valueOf(row.get("udt_name"));
        Object cml = row.get("character_maximum_length");
        Object np = row.get("numeric_precision");
        Object ns = row.get("numeric_scale");
        if (cml instanceof Number) {
            return udt + "(" + ((Number) cml).intValue() + ")";
        }
        if ("numeric".equalsIgnoreCase(udt) && np instanceof Number) {
            int p = ((Number) np).intValue();
            int s = (ns instanceof Number) ? ((Number) ns).intValue() : 0;
            return udt + "(" + p + "," + s + ")";
        }
        return udt;
    }
}
