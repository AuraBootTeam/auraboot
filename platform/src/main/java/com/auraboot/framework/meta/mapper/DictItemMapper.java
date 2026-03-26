package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DictItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 字典项Mapper接口
 * 对应表：ab_dict_item
 * 
 * 重构说明：
 * - 添加幂等insert方法供ProjectionEngine使用
 */
@Mapper
public interface DictItemMapper extends BaseMapper<DictItem> {

    // ==================== 幂等INSERT方法（统一使用） ====================
    
    /**
     * 插入字典项（幂等）
     * 
     * 使用 ON CONFLICT DO NOTHING 保证幂等性
     * 
     * @param item 字典项实体
     * @return 实际插入的行数（0=已存在跳过, 1=新插入成功）
     */
    @Insert("""
        INSERT INTO ab_dict_item
        (pid, dict_id, value, label, parent_value, sort_no,
         tenant_id, status, source, created_at, updated_at)
        VALUES
        (#{pid}, #{dictId}, #{value}, #{label}, #{parentValue}, #{sortNo},
         #{tenantId}, #{status}, #{source},
         #{createdAt}, #{updatedAt})
        ON CONFLICT (dict_id, value) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(DictItem item);

    // ==================== 标准查询方法 ====================

    /**
     * 根据字典ID查询所有字典项
     * @param dictId 字典ID
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND status = 'enabled' ORDER BY sort_no ASC, value ASC")
    List<DictItem> findByDictId(@Param("dictId") Long dictId);

    /**
     * 根据字典ID查询所有字典项（包含禁用的）
     * @param dictId 字典ID
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} ORDER BY sort_no ASC, value ASC")
    List<DictItem> findAllByDictId(@Param("dictId") Long dictId);

    /**
     * 根据字典ID和父级值查询子项
     * @param dictId 字典ID
     * @param parentValue 父级值
     * @return 子项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND parent_value = #{parentValue} AND status = 'enabled' ORDER BY sort_no ASC, value ASC")
    List<DictItem> findByDictIdAndParentValue(@Param("dictId") Long dictId, @Param("parentValue") String parentValue);

    /**
     * 根据字典ID查询顶级项
     * @param dictId 字典ID
     * @return 顶级项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND (parent_value IS NULL OR parent_value = '') AND status = 'enabled' ORDER BY sort_no ASC, value ASC")
    List<DictItem> findTopLevelByDictId(@Param("dictId") Long dictId);

    /**
     * 根据字典ID和值查询字典项
     * @param dictId 字典ID
     * @param value 字典项值
     * @return 字典项
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND value = #{value}")
    DictItem findByDictIdAndValue(@Param("dictId") Long dictId, @Param("value") String value);

    /**
     * 根据租户ID查询字典项（依赖MyBatis-Plus租户拦截器自动注入tenant_id）
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item ORDER BY dict_id, sort_no ASC")
    List<DictItem> findByTenant();

    /**
     * 检查字典项值是否存在
     * @param dictId 字典ID
     * @param value 字典项值
     * @param excludeId 排除的ID
     * @return 存在数量
     */
    @Select("SELECT COUNT(*) FROM ab_dict_item WHERE dict_id = #{dictId} AND value = #{value} AND (#{excludeId} IS NULL OR id != #{excludeId})")
    int countByDictIdAndValue(@Param("dictId") Long dictId, @Param("value") String value, @Param("excludeId") Long excludeId);

    /**
     * 获取指定字典的字典项数量
     * @param dictId 字典ID
     * @return 字典项数量
     */
    @Select("SELECT COUNT(*) FROM ab_dict_item WHERE dict_id = #{dictId}")
    int countByDictId(@Param("dictId") Long dictId);

    /**
     * 获取指定字典和父级值的最大排序号
     * @param dictId 字典ID
     * @param parentValue 父级值
     * @return 最大排序号
     */
    @Select("SELECT COALESCE(MAX(sort_no), -1) FROM ab_dict_item WHERE dict_id = #{dictId} AND (#{parentValue} IS NULL AND (parent_value IS NULL OR parent_value = '') OR parent_value = #{parentValue})")
    Integer getMaxSortNo(@Param("dictId") Long dictId, @Param("parentValue") String parentValue);

    /**
     * 删除指定字典的所有字典项
     * @param dictId 字典ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_dict_item WHERE dict_id = #{dictId}")
    int deleteByDictId(@Param("dictId") Long dictId);

    /**
     * Delete dict items by dictId and source (PLUGIN or USER)
     * Used during reimport to only replace plugin-owned items while preserving user-added items
     */
    @Delete("DELETE FROM ab_dict_item WHERE dict_id = #{dictId} AND source = #{source}")
    int deleteByDictIdAndSource(@Param("dictId") Long dictId, @Param("source") String source);

    /**
     * 删除指定字典和父级值的所有子项
     * @param dictId 字典ID
     * @param parentValue 父级值
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_dict_item WHERE dict_id = #{dictId} AND parent_value = #{parentValue}")
    int deleteByDictIdAndParentValue(@Param("dictId") Long dictId, @Param("parentValue") String parentValue);

    /**
     * 更新字典项状态
     * @param id 字典项ID
     * @param status 新状态
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict_item SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 批量更新字典项状态
     * @param dictId 字典ID
     * @param status 新状态
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict_item SET status = #{status} WHERE dict_id = #{dictId}")
    int updateStatusByDictId(@Param("dictId") Long dictId, @Param("status") String status);

    /**
     * Update source for all items of a dict
     * Used during plugin import to mark items as PLUGIN-sourced
     */
    @Update("UPDATE ab_dict_item SET source = #{source} WHERE dict_id = #{dictId}")
    int updateSourceByDictId(@Param("dictId") Long dictId, @Param("source") String source);

    /**
     * 更新字典项排序
     * @param id 字典项ID
     * @param sortNo 新排序号
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict_item SET sort_no = #{sortNo} WHERE id = #{id}")
    int updateSortNo(@Param("id") Long id, @Param("sortNo") Integer sortNo);

    /**
     * 调整排序 - 将指定排序值之后的项排序值加1
     * @param dictId 字典ID
     * @param parentValue 父级值
     * @param fromSortNo 起始排序值
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict_item SET sort_no = sort_no + 1 WHERE dict_id = #{dictId} AND (#{parentValue} IS NULL AND (parent_value IS NULL OR parent_value = '') OR parent_value = #{parentValue}) AND sort_no >= #{fromSortNo}")
    int incrementSortNoFrom(@Param("dictId") Long dictId, @Param("parentValue") String parentValue, @Param("fromSortNo") Integer fromSortNo);

    /**
     * 调整排序 - 将指定排序值之后的项排序值减1
     * @param dictId 字典ID
     * @param parentValue 父级值
     * @param fromSortNo 起始排序值
     * @return 更新的记录数
     */
    @Update("UPDATE ab_dict_item SET sort_no = sort_no - 1 WHERE dict_id = #{dictId} AND (#{parentValue} IS NULL AND (parent_value IS NULL OR parent_value = '') OR parent_value = #{parentValue}) AND sort_no > #{fromSortNo}")
    int decrementSortNoFrom(@Param("dictId") Long dictId, @Param("parentValue") String parentValue, @Param("fromSortNo") Integer fromSortNo);

    /**
     * 根据标签模糊查询字典项
     * @param dictId 字典ID
     * @param labelPattern 标签模式
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND label LIKE #{labelPattern} AND status = 'enabled' ORDER BY sort_no ASC")
    List<DictItem> findByDictIdAndLabelPattern(@Param("dictId") Long dictId, @Param("labelPattern") String labelPattern);

    /**
     * 根据值模糊查询字典项
     * @param dictId 字典ID
     * @param valuePattern 值模式
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND value LIKE #{valuePattern} AND status = 'enabled' ORDER BY sort_no ASC")
    List<DictItem> findByDictIdAndValuePattern(@Param("dictId") Long dictId, @Param("valuePattern") String valuePattern);

    /**
     * 根据字典ID和状态查询所有字典项（用于构建级联树）
     * @param dictId 字典ID
     * @param status 状态
     * @return 字典项列表
     */
    @Select("SELECT * FROM ab_dict_item WHERE dict_id = #{dictId} AND status = #{status} ORDER BY sort_no ASC, value ASC")
    List<DictItem> selectByDictIdAndStatus(@Param("dictId") Long dictId, @Param("status") String status);

    /**
     * 物理删除测试数据 - 根据租户和命名空间删除记录（依赖租户拦截器）
     * @return 删除的记录数
     */
    @Delete("DELETE FROM ab_dict_item")
    int deleteByTenant ();
}
