package com.auraboot.framework.file.dao.mapper;

import com.auraboot.framework.file.entity.FileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文件信息Mapper接口
 */
@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {
    
    /**
     * 根据创建用户ID查询文件列表
     */
    @Select("SELECT * FROM ab_file WHERE created_by = #{userId} AND status = 'active' AND deleted_flag = false ORDER BY upload_time DESC")
    List<FileEntity> selectByCreatedBy(@Param("userId") Long userId);

    /**
     * 根据存储类型查询文件列表
     */
    @Select("SELECT * FROM ab_file WHERE storage_type = #{storageType} AND status = 'active' AND deleted_flag = false")
    List<FileEntity> selectByStorageType(@Param("storageType") String storageType);

    /**
     * 根据文件状态查询文件列表
     */
    @Select("SELECT * FROM ab_file WHERE status = #{status} AND deleted_flag = false")
    List<FileEntity> selectByStatus(@Param("status") String status);

    /**
     * 根据文件扩展名查询文件列表
     */
    @Select("SELECT * FROM ab_file WHERE file_extension = #{extension} AND status = 'active' AND deleted_flag = false")
    List<FileEntity> selectByExtension(@Param("extension") String extension);
}