package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.NamedQueryVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_named_query_version table.
 */
@Mapper
public interface NamedQueryVersionMapper extends BaseMapper<NamedQueryVersion> {

    @Select("SELECT * FROM ab_named_query_version WHERE query_code = #{queryCode} ORDER BY version_no DESC")
    List<NamedQueryVersion> findByQueryCode(@Param("queryCode") String queryCode);

    @Select("SELECT * FROM ab_named_query_version WHERE query_code = #{queryCode} AND version_no = #{versionNo}")
    NamedQueryVersion findByQueryCodeAndVersion(@Param("queryCode") String queryCode, @Param("versionNo") int versionNo);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM ab_named_query_version WHERE query_code = #{queryCode}")
    int getMaxVersionNo(@Param("queryCode") String queryCode);

    @Select("SELECT * FROM ab_named_query_version WHERE pid = #{pid}")
    NamedQueryVersion findByPid(@Param("pid") String pid);
}
