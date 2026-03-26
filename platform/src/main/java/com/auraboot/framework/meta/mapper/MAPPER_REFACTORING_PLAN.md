# Mapper层重构计划

## 问题

当前设计中，`ProjectionMapper` 和业务Mapper（如`MetaModelMapper`）都操作同一张表，导致：

1. SQL重复定义
2. 维护成本高
3. 职责不清
4. 容易出现不一致

## 重构目标

**一个表只有一个Mapper** - 遵循单一职责原则

## 重构方案

### 阶段1: 扩展业务Mapper（统一INSERT方法）

在现有的业务Mapper中，**只提供一个带幂等性的insert方法**：

```java
@Mapper
public interface MetaModelMapper extends BaseMapper<MetaModel> {
    
    // ==================== 统一的INSERT方法 ====================
    
    /**
     * 插入模型（幂等）
     * 
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     * 
     * 适用场景：
     * - Service层直接创建
     * - ProjectionEngine投影
     * 
     * @param model 模型实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_meta_model
        (pid, tenant_id,   code, extension, version,
         semver, is_current, row_version, status, deleted_flag, 
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{namespace}, #{env}, #{code}, 
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{releaseId}, #{releasePid}, #{projectedAt}, #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MetaModel model);
    
    /**
     * 批量插入（幂等）
     * 建议批量大小：≤ 500条
     */
    @Insert("""
        <script>
        INSERT INTO ab_meta_model
        (pid, tenant_id,   code, extension, version,
         semver, is_current, row_version, status, deleted_flag,
         release_id, release_pid, projected_at, created_at, updated_at)
        VALUES
        <foreach collection="models" item="m" separator=",">
        (#{m.pid}, #{m.tenantId}, #{m.namespace}, #{m.env}, #{m.code},
         #{m.extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{m.version}, #{m.semver}, #{m.isCurrent}, #{m.rowVersion}, #{m.status}, #{m.deletedFlag},
         #{m.releaseId}, #{m.releasePid}, #{m.projectedAt}, #{m.createdAt}, #{m.updatedAt})
        </foreach>
        ON CONFLICT (tenant_id,   code, version) DO NOTHING
        </script>
        """)
    int batchInsert(@Param("models") List<MetaModel> models);
    
    // ==================== 标准CRUD方法 ====================
    
    int updateById(MetaModel model);
    MetaModel selectById(Long id);
    MetaModel findByPid(String pid);
    MetaModel findCurrentByCode(String code);
    
    // ==================== 投影辅助方法 ====================
    
    /**
     * 标记旧版本为非当前
     */
    @Update("UPDATE ab_meta_model SET is_current = false " +
            "WHERE tenant_id = #{tenantId} AND namespace = #{namespace} " +
            "AND code = #{code}")
    int markAsNotCurrent(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code
    );
    
    /**
     * 检查版本是否已存在（幂等性检查）
     */
    @Select("SELECT COUNT(*) FROM ab_meta_model " +
            "WHERE tenant_id = #{tenantId} AND namespace = #{namespace} " +
            "AND code = #{code} AND version = #{version}")
    int countByVersion(
        @Param("tenantId") Long tenantId,
             
             
        @Param("code") String code,
        @Param("version") Integer version
    );
}
```

**关键点：只有一个insert方法，带ON CONFLICT保证幂等性**

### 阶段2: 修改ProjectionEngine（使用统一的insert方法）

```java
@Service
@RequiredArgsConstructor
public class ProjectionEngine {
    
    // ✅ 直接使用业务Mapper
    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final DictMapper dictMapper;
    private final PageSchemaMapper pageSchemaMapper;
    
    public void projectModelArtifact(ModelArtifact artifact, Release release) {
        log.info("投影Model artifact: {}", artifact.getCode());
        
        // 1. 构建实体
        MetaModel model = buildMetaModelFromArtifact(artifact, release);
        
        // 2. 标记旧版本
        metaModelMapper.markAsNotCurrent(
            model.getTenantId(), model.getNamespace(),
            model.getEnv(), model.getCode()
        );
        
        // 3. 插入新版本（幂等）- 使用统一的insert方法
        int inserted = metaModelMapper.insert(model);  // ✅ 统一方法
        
        if (inserted > 0) {
            log.info("Model投影成功: code={}, version={}", 
                    model.getCode(), model.getVersion());
        } else {
            log.info("Model已存在，跳过投影: code={}, version={}", 
                    model.getCode(), model.getVersion());
        }
    }
    
    /**
     * 批量投影（性能优化）
     */
    public void projectModelArtifactsBatch(List<ModelArtifact> artifacts, Release release) {
        if (artifacts.isEmpty()) {
            return;
        }
        
        // 构建实体列表
        List<MetaModel> models = artifacts.stream()
            .map(artifact -> buildMetaModelFromArtifact(artifact, release))
            .collect(Collectors.toList());
        
        // 分批插入（每批500条）- 使用统一的batchInsert方法
        int batchSize = 500;
        for (int i = 0; i < models.size(); i += batchSize) {
            int end = Math.min(i + batchSize, models.size());
            List<MetaModel> batch = models.subList(i, end);
            
            int inserted = metaModelMapper.batchInsert(batch);  // ✅ 统一方法
            log.info("批量投影Model: 尝试={}, 成功={}", batch.size(), inserted);
        }
    }
    
    private MetaModel buildMetaModelFromArtifact(ModelArtifact artifact, Release release) {
        MetaModel model = new MetaModel();
        model.setPid(artifact.getPid());
        model.setTenantId(artifact.getTenantId());
        model.setNamespace(artifact.getNamespace());
        model.setEnv(artifact.getEnv());
        model.setCode(artifact.getCode());
        
        // 设置Extension
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(artifact.getExtension());
        model.setExtension(extension);
        
        // 版本信息
        model.setVersion(artifact.getVersion());
        model.setSemver(artifact.getSemver());
        model.setIsCurrent(true);
        model.setRowVersion(1);
        
        // 状态
        model.setStatus("PUBLISHED");
        model.setDeletedFlag(false);
        
        // Release关联
        model.setReleaseId(release.getId());
        model.setReleasePid(release.getPid());
        
        // 时间戳
        Instant now = Instant.now();
        model.setProjectedAt(now);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        
        return model;
    }
}
```

### Service层使用（如果需要严格检查）

```java
@Service
public class MetaModelServiceImpl {
    
    private final MetaModelMapper metaModelMapper;
    
    public MetaModelDTO createDirectly(MetaModelCreateRequest request) {
        // 1. 先检查是否存在（严格模式）
        MetaModel existing = metaModelMapper.findCurrentByCode(request.getCode());
        if (existing != null) {
            throw new DuplicateException("模型已存在: " + request.getCode());
        }
        
        // 2. 构建实体
        MetaModel model = buildMetaModelEntity(request);
        
        // 3. 插入（使用统一的insert方法）
        int inserted = metaModelMapper.insert(model);  // ✅ 统一方法
        
        // 4. 双重检查（理论上不会发生，因为已经检查过）
        if (inserted == 0) {
            throw new ConcurrentModificationException("并发创建冲突");
        }
        
        return convertToDTO(model);
    }
}
```

**关键点：Service层和ProjectionEngine使用同一个insert方法**

### 阶段3: 删除ProjectionMapper

```bash
# 删除文件
rm platform/src/main/java/com/auraboot/framework/git/mapper/ProjectionMapper.java

# 删除相关的DTO（如果只用于ProjectionMapper）
rm platform/src/main/java/com/auraboot/framework/git/dto/ModelProjectionDTO.java
rm platform/src/main/java/com/auraboot/framework/git/dto/FieldProjectionDTO.java
```

## 对比

### 修复前

```
ProjectionMapper (git模块)
├── insertModel()           ← 向ab_meta_model插入
├── insertField()           ← 向ab_meta_field插入
├── insertDict()            ← 向ab_dict插入
└── ...

MetaModelMapper (meta模块)
├── insert()                ← 向ab_meta_model插入
├── updateById()
└── ...

问题：两个Mapper操作同一张表！
```

### 修复后

```
MetaModelMapper (meta模块) - 唯一负责ab_meta_model表
├── insert()                ← Service层使用
├── insertForProjection()   ← ProjectionEngine使用
├── batchInsertForProjection() ← 批量投影
├── markAsNotCurrent()      ← 投影辅助方法
└── ...

ProjectionEngine (git模块)
└── 使用 MetaModelMapper   ← 不再有自己的Mapper
```

## 实施步骤

### Step 1: 扩展MetaModelMapper

在 `MetaModelMapper` 中添加投影专用方法：
- `insertForProjection()`
- `batchInsertForProjection()`
- `markAsNotCurrent()`
- `countByVersion()`

### Step 2: 修改ProjectionEngine

- 注入 `MetaModelMapper` 替代 `ProjectionMapper`
- 修改 `projectModelArtifact()` 方法
- 添加 `buildMetaModelFromArtifact()` 辅助方法

### Step 3: 同样处理Field、Dict、Page

- 扩展 `MetaFieldMapper`
- 扩展 `DictMapper`
- 扩展 `PageSchemaMapper`

### Step 4: 删除ProjectionMapper

- 删除 `ProjectionMapper.java`
- 删除相关的DTO
- 更新测试

### Step 5: 测试验证

- 单元测试：验证Mapper方法
- 集成测试：验证投影流程
- 性能测试：验证批量投影性能

## 收益

1. **代码简化** - 删除重复的SQL定义
2. **职责清晰** - 一个表一个Mapper
3. **易于维护** - 表结构变化只需改一处
4. **类型安全** - 使用实体对象而不是散列参数
5. **性能优化** - 批量操作更容易实现

## 风险评估

- **风险等级**: 低
- **影响范围**: ProjectionEngine和相关测试
- **回滚方案**: Git revert
- **测试覆盖**: 需要完整的集成测试

## 时间估算

- Step 1-2: 2小时
- Step 3: 3小时
- Step 4-5: 2小时
- **总计**: 1个工作日
