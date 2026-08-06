package com.auraboot.framework.file.dao.mapper;

import com.auraboot.framework.file.entity.FileEntity;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 文件信息Mapper接口
 */
@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {

    /**
     * Lock one tenant-owned public file row before retention/deletion arbitration.
     * Tenant interception is bypassed only because the SQL carries the mandatory tenant predicate.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ab_file WHERE tenant_id = #{tenantId} AND pid = #{pid} "
            + "AND deleted_flag = false FOR UPDATE")
    FileEntity selectActiveByPidForUpdate(@Param("tenantId") Long tenantId,
                                          @Param("pid") String pid);

    /** Legacy numeric-id variant of {@link #selectActiveByPidForUpdate(Long, String)}. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ab_file WHERE tenant_id = #{tenantId} AND id = #{id} "
            + "AND deleted_flag = false FOR UPDATE")
    FileEntity selectActiveByIdForUpdate(@Param("tenantId") Long tenantId,
                                         @Param("id") Long id);

    /**
     * Acquire the deletion fence while the row is locked. A retention lock always wins and makes
     * this update affect zero rows. Physical deletion is scheduled only after this update commits.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ab_file SET status = 'deleted', deleted_flag = true, updated_time = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_flag = false "
            + "AND retention_locked = false")
    int markDeletedIfUnlocked(@Param("tenantId") Long tenantId,
                              @Param("id") Long id,
                              @Param("updatedAt") Instant updatedAt);

    /** Finalized multipart uploads may be retained exactly once; the flag is monotonic. */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ab_file SET retention_locked = true, updated_time = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_flag = false "
            + "AND status = 'success' AND retention_locked = false")
    int lockRetentionIfFinal(@Param("tenantId") Long tenantId,
                             @Param("id") Long id,
                             @Param("updatedAt") Instant updatedAt);

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
