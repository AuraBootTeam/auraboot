package com.auraboot.framework.file.dao.mapper;

import com.auraboot.framework.file.entity.FileRelationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文件关联关系Mapper接口
 */
@Mapper
public interface FileRelationMapper extends BaseMapper<FileRelationEntity> {
    
    /**
     * 根据实体信息查询关联的文件ID列表
     */
    @Select("SELECT file_id FROM ab_file_relation WHERE entity_type = #{entityType} AND entity_id = #{entityId} AND deleted_flag = false ORDER BY sort_order ASC")
    List<String> findFileIdsByEntity(@Param("entityType") String entityType, @Param("entityId") String entityId);

    /**
     * 根据实体和字段查询文件ID列表
     */
    @Select("SELECT file_id FROM ab_file_relation WHERE entity_type = #{entityType} AND entity_id = #{entityId} AND field_name = #{fieldName} AND deleted_flag = false ORDER BY sort_order ASC")
    List<String> findFileIdsByEntityAndField(@Param("entityType") String entityType, @Param("entityId") String entityId, @Param("fieldName") String fieldName);
}