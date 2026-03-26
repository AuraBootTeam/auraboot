package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplacePlugin;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface MarketplacePluginMapper extends BaseMapper<MarketplacePlugin> {

    @Select("SELECT * FROM ab_marketplace_plugin WHERE pid = #{pid} AND deleted_flag = FALSE")
    MarketplacePlugin findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_marketplace_plugin WHERE plugin_id = #{pluginId} AND deleted_flag = FALSE")
    MarketplacePlugin findByPluginId(@Param("pluginId") String pluginId);

    @Select("SELECT * FROM ab_marketplace_plugin WHERE status = 'published' AND deleted_flag = FALSE ORDER BY install_count DESC")
    List<MarketplacePlugin> findPublished();

    @Select("SELECT * FROM ab_marketplace_plugin WHERE status = 'published' AND category_code = #{categoryCode} AND deleted_flag = FALSE ORDER BY install_count DESC")
    List<MarketplacePlugin> findByCategory(@Param("categoryCode") String categoryCode);

    @Select("SELECT * FROM ab_marketplace_plugin WHERE status = 'published' AND featured = TRUE AND deleted_flag = FALSE ORDER BY install_count DESC")
    List<MarketplacePlugin> findFeatured();

    @Select("SELECT * FROM ab_marketplace_plugin WHERE status = 'in_review' AND deleted_flag = FALSE ORDER BY created_at ASC")
    List<MarketplacePlugin> findPendingReview();

    @Select("SELECT * FROM ab_marketplace_plugin WHERE status = 'published' AND deleted_flag = FALSE AND (LOWER(display_name) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(display_name_en) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(display_name_zh) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(plugin_id) LIKE LOWER(CONCAT('%', #{keyword}, '%')) OR LOWER(summary) LIKE LOWER(CONCAT('%', #{keyword}, '%'))) ORDER BY install_count DESC")
    List<MarketplacePlugin> searchPublished(@Param("keyword") String keyword);

    @Update("UPDATE ab_marketplace_plugin SET install_count = install_count + 1, updated_at = NOW() WHERE pid = #{pid}")
    int incrementInstallCount(@Param("pid") String pid);

    @Update("UPDATE ab_marketplace_plugin SET status = #{status}, updated_at = NOW() WHERE pid = #{pid}")
    int updateStatus(@Param("pid") String pid, @Param("status") String status);

    @Update("UPDATE ab_marketplace_plugin SET average_rating = #{averageRating}, review_count = #{reviewCount}, updated_at = NOW() WHERE pid = #{pid}")
    int updateReviewStats(@Param("pid") String pid, @Param("averageRating") java.math.BigDecimal averageRating, @Param("reviewCount") int reviewCount);
}
