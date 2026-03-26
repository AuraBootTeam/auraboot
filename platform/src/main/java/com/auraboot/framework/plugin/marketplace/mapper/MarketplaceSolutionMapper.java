package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplaceSolution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface MarketplaceSolutionMapper extends BaseMapper<MarketplaceSolution> {

    @Select("SELECT * FROM ab_marketplace_solution WHERE pid = #{pid} AND deleted_flag = FALSE")
    MarketplaceSolution findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_marketplace_solution WHERE code = #{code} AND deleted_flag = FALSE")
    MarketplaceSolution findByCode(@Param("code") String code);

    @Select("SELECT * FROM ab_marketplace_solution WHERE status = 'published' AND deleted_flag = FALSE ORDER BY sort_order ASC, install_count DESC")
    List<MarketplaceSolution> findPublished();

    @Select("SELECT * FROM ab_marketplace_solution WHERE status = 'published' AND industry = #{industry} AND deleted_flag = FALSE ORDER BY sort_order ASC, install_count DESC")
    List<MarketplaceSolution> findByIndustry(@Param("industry") String industry);

    @Select("SELECT * FROM ab_marketplace_solution WHERE status = 'published' AND featured = TRUE AND deleted_flag = FALSE ORDER BY sort_order ASC")
    List<MarketplaceSolution> findFeatured();

    @Select("SELECT * FROM ab_marketplace_solution WHERE status = 'published' AND deleted_flag = FALSE AND (LOWER(name) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(name_en) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(name_zh) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(description) LIKE LOWER(CONCAT('%', #{keyword}, '%'))) ORDER BY install_count DESC")
    List<MarketplaceSolution> searchPublished(@Param("keyword") String keyword);

    @Select("SELECT * FROM ab_marketplace_solution WHERE deleted_flag = FALSE ORDER BY sort_order ASC, created_at DESC")
    List<MarketplaceSolution> findAll();

    @Update("UPDATE ab_marketplace_solution SET install_count = install_count + 1, updated_at = NOW() WHERE pid = #{pid}")
    int incrementInstallCount(@Param("pid") String pid);

    @Update("UPDATE ab_marketplace_solution SET install_count = GREATEST(install_count - 1, 0), updated_at = NOW() WHERE pid = #{pid}")
    int decrementInstallCount(@Param("pid") String pid);

    @Update("UPDATE ab_marketplace_solution SET status = #{status}, updated_at = NOW(), published_at = CASE WHEN #{status} = 'published' THEN NOW() ELSE published_at END WHERE pid = #{pid}")
    int updateStatus(@Param("pid") String pid, @Param("status") String status);
}
